package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.time.BusinessTime;
import jakarta.persistence.EntityManager;
import com.storagehub.domain.repo.UserRepository;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** A recovery reserves its Staff for the planned execution window, until completion or cancellation. */
@Service
@RequiredArgsConstructor
public class RecoveryStaffAvailability {
    private static final Duration APPOINTMENT = Duration.ofMinutes(30);
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public void lock(Collection<UUID> ids) {
        if (ids.stream().anyMatch(Objects::isNull)) throw ApiExceptions.validation("Cần chọn nhân viên.", null);
        for (var id : ids.stream().distinct().sorted().toList()) {
            users.findByIdForUpdate(id).orElseThrow(() -> ApiExceptions.validation("Không tìm thấy nhân viên.", null));
        }
    }

    public boolean available(UUID staff, ViolationRecovery own, Instant start, Instant end) {
        if (staff == null || start == null || end == null || !end.isAfter(start)) return false;
        var recoveries = em.createQuery("""
            select v from ViolationRecovery v left join v.task t
            where (t.assignedTo.id=:staff or t.recoveryWitness.id=:staff)
              and v.stage not in :closed and v.id<>:own
            """, ViolationRecovery.class).setParameter("staff", staff).setParameter("own", own.getId())
            .setParameter("closed", List.of(ViolationRecovery.Stage.COMPLETED, ViolationRecovery.Stage.CANCELLED)).getResultList();
        for (var other : recoveries) {
            var finish = other.getDeadlineAt();
            if (other.getTask() != null && other.getTask().getStatus() == MaintenanceTaskStatus.in_progress
                && finish != null && finish.isBefore(start)) finish = Instant.MAX;
            if (overlap(start, end, other.getScheduledAt(), finish)) return false;
        }
        var tasks = em.createQuery("""
            select t from MaintenanceTask t
            where (t.assignedTo.id=:staff or t.recoveryWitness.id=:staff)
              and t.status in :active and t.recoveryType<>:violation
            """, MaintenanceTask.class).setParameter("staff", staff)
            .setParameter("active", List.of(MaintenanceTaskStatus.open, MaintenanceTaskStatus.in_progress))
            .setParameter("violation", MaintenanceRecoveryType.VIOLATION).getResultList();
        for (var task : tasks) {
            if (own.getTask() != null && own.getTask().getId().equals(task.getId())) continue;
            var from = task.getStartedAt() != null ? task.getStartedAt()
                : task.getDueAt() != null ? task.getDueAt().atStartOfDay(BusinessTime.ZONE).toInstant() : task.getCreatedAt();
            var until = task.getDeadlineAt() != null ? task.getDeadlineAt()
                : task.getDueAt() == null ? Instant.MAX : task.getDueAt().plusDays(1).atStartOfDay(BusinessTime.ZONE).toInstant();
            if (task.getStatus() == MaintenanceTaskStatus.in_progress && until.isBefore(start)) until = Instant.MAX;
            if (overlap(start, end, from == null ? Instant.MIN : from, until)) return false;
        }
        var checkIns = em.createQuery("""
            select c from CheckIn c left join c.rental r
            where (c.assignedStaff.id=:staff or (c.assignedStaff is null and c.performedBy.id=:staff))
              and c.status not in :closed and (r is null or r.inventoryEscalatedAt is null)
            """, CheckIn.class).setParameter("staff", staff)
            .setParameter("closed", List.of(CheckInStatus.completed, CheckInStatus.cancelled, CheckInStatus.rejected, CheckInStatus.no_show))
            .getResultList();
        for (var checkIn : checkIns) {
            if (checkIn.getRental() != null && own.getRental().getId().equals(checkIn.getRental().getId())) continue;
            var at = checkIn.getStatus() == CheckInStatus.setup_completed ? checkIn.getInventoryAppointmentAt()
                : checkIn.getStatus() == CheckInStatus.setup_in_progress || checkIn.getStatus() == CheckInStatus.handover_pending_receipt
                ? null : checkIn.getReservation().getAppointmentAt() != null ? checkIn.getReservation().getAppointmentAt() : checkIn.getScheduledAt();
            if (at != null && overlap(start, end, at, at.plus(APPOINTMENT))) return false;
        }
        var returns = em.createQuery("""
            select r from ReturnCase r where
              (r.postReviewHandoverStaffId=:staff or r.returnReverificationStaffId=:staff)
              and r.inspectedAt is null and r.status not in :closed
            """, ReturnCase.class).setParameter("staff", staff)
            .setParameter("closed", List.of(ReturnCaseStatus.completed, ReturnCaseStatus.withdrawn_for_recovery)).getResultList();
        for (var returned : returns) {
            if (own.getRental().getId().equals(returned.getRental().getId())) continue;
            var at = staff.equals(returned.getPostReviewHandoverStaffId()) ? returned.getPostReviewHandoverAppointmentAt()
                : returned.getReturnReverificationStartedAt();
            if (at != null && overlap(start, end, at, at.plus(APPOINTMENT))) return false;
        }
        // This optional calendar is not deployed in every installation.
        boolean signingCalendar = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "renewal_signing_reservations", new String[]{"TABLE"})) {
                return tables.next();
            }
        }));
        if (!signingCalendar) return true;
        Long signing = jdbc.queryForObject("""
            select count(*) from renewal_signing_reservations
            where staff_id=? and status='RESERVED' and appointment_start<? and appointment_end>?
            """, Long.class, bytes(staff), Timestamp.from(end), Timestamp.from(start));
        return signing != null && signing == 0;
    }

    static boolean overlap(Instant start, Instant end, Instant otherStart, Instant otherEnd) {
        return otherStart != null && otherEnd != null && otherStart.isBefore(end) && otherEnd.isAfter(start);
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
