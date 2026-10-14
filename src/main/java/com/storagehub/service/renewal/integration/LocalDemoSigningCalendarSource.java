package com.storagehub.service.renewal.integration;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.Renewal;
import com.storagehub.domain.model.Rental;
import com.storagehub.service.renewal.operations.RenewalOperationSources;
import com.storagehub.service.calendar.ServiceCalendarPolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local-demo signing calendar: Vietnam time, daily 08:00-17:00, 30-minute slots, one at a time per facility. */
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${storagehub.integration.local-demo-operational-sources.enabled:false} && !${storagehub.integration.renewal-signing-calendar.enabled:false}")
@Transactional(propagation = Propagation.MANDATORY)
public class LocalDemoSigningCalendarSource implements RenewalOperationSources.CalendarSource {
    private static final Duration SLOT_LENGTH = Duration.ofMinutes(30);
    private final JdbcTemplate jdbc;
    private final EntityManager em;

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean atomic() {
        var dataSource = jdbc.getDataSource();
        return TransactionSynchronizationManager.isActualTransactionActive() && dataSource != null
            && TransactionSynchronizationManager.hasResource(dataSource);
    }

    @Override
    public java.util.Optional<Boolean> feasible(Rental rental, Instant now, Instant deadline) {
        if (rental == null || rental.getFacility() == null || rental.getFacility().getId() == null
            || now == null || deadline == null) return java.util.Optional.empty();
        return java.util.Optional.of(hasAvailableSlot(rental.getFacility().getId(), now, deadline));
    }

    @Override
    public java.util.Optional<RenewalOperationSources.Slot> slot(Renewal renewal, Instant start) {
        if (renewal == null || renewal.getId() == null || renewal.getRental() == null
            || renewal.getRental().getFacility() == null || renewal.getRental().getFacility().getId() == null || start == null)
            return java.util.Optional.empty();
        Instant end = start.plus(SLOT_LENGTH);
        if (!isBusinessSlot(start) || !available(
            renewal.getRental().getFacility().getId(), start, end, renewal.getId())) return java.util.Optional.empty();
        return java.util.Optional.of(new RenewalOperationSources.Slot(reference(renewal.getRental().getFacility().getId(), start), start, end));
    }

    @Override
    public boolean feasible(Renewal renewal, Instant start, Instant deadline) {
        if (renewal == null || renewal.getRental() == null || renewal.getRental().getFacility() == null
            || start == null || deadline == null || !deadline.isAfter(start)) return false;
        return hasAvailableSlot(renewal.getRental().getFacility().getId(), start, deadline);
    }

    @Override
    public void reserve(Renewal renewal, RenewalOperationSources.Slot slot, Instant now) {
        if (renewal == null || renewal.getId() == null || renewal.getRental() == null
            || renewal.getRental().getFacility() == null || slot == null || now == null
            || !TransactionSynchronizationManager.isActualTransactionActive()
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Signing slot reservation requires a complete writable transaction");
        }
        UUID facilityId = renewal.getRental().getFacility().getId();
        // Serializes every local-demo booking for this facility across application instances.
        Facility facility = em.find(Facility.class, facilityId, LockModeType.PESSIMISTIC_WRITE);
        if (facility == null) throw ApiExceptions.conflict("Signing facility is unavailable");
        em.refresh(facility, LockModeType.PESSIMISTIC_WRITE);
        if (facility.getStatus() != com.storagehub.domain.model.FacilityStatus.active)
            throw ApiExceptions.conflict("Signing facility is not active");

        if (!slot.start().isAfter(now) || !slot.end().equals(slot.start().plus(SLOT_LENGTH))
            || !isBusinessSlot(slot.start())
            || !slot.reference().equals(reference(facilityId, slot.start()))) {
            throw ApiExceptions.conflict("Signing slot changed; select another time");
        }
        var current = jdbc.query(
            "select id from renewal_demo_signing_appointments where renewal_id=? for update",
            (rs, row) -> uuid(rs.getBytes("id")), bytes(renewal.getId()));
        if (!available(facilityId, slot.start(), slot.end(), renewal.getId())) {
            throw ApiExceptions.conflict("Signing slot was taken; select another time");
        }

        if (current.isEmpty()) {
            Instant at = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            jdbc.update("insert into renewal_demo_signing_appointments(id,facility_id,renewal_id,appointment_start,appointment_end,status,created_at,updated_at,version) values(?,?,?,?,?,'RESERVED',?,?,0)",
                bytes(UUID.randomUUID()), bytes(facilityId), bytes(renewal.getId()), Timestamp.from(slot.start()),
                Timestamp.from(slot.end()), Timestamp.from(at), Timestamp.from(at));
        } else {
            jdbc.update("update renewal_demo_signing_appointments set facility_id=?,appointment_start=?,appointment_end=?,status='RESERVED',updated_at=?,version=version+1 where id=?",
                bytes(facilityId), Timestamp.from(slot.start()), Timestamp.from(slot.end()),
                Timestamp.from(now.truncatedTo(java.time.temporal.ChronoUnit.MICROS)), bytes(current.getFirst()));
        }
    }

    private boolean hasAvailableSlot(UUID facilityId, Instant from, Instant deadline) {
        if (!deadline.isAfter(from) || Duration.between(from, deadline).compareTo(Duration.ofDays(366)) > 0) return false;
        var busy = jdbc.query("select appointment_start,appointment_end from renewal_demo_signing_appointments where facility_id=? and status='RESERVED' and appointment_start<? and appointment_end>?",
            (rs, row) -> new Interval(rs.getTimestamp("appointment_start").toInstant(), rs.getTimestamp("appointment_end").toInstant()),
            bytes(facilityId), Timestamp.from(deadline), Timestamp.from(from));
        Instant candidate = ServiceCalendarPolicy.nextAppointmentAt(from);
        for (; candidate.plus(SLOT_LENGTH).compareTo(deadline) <= 0; candidate = candidate.plus(SLOT_LENGTH)) {
            Instant slotStart = candidate;
            Instant slotEnd = slotStart.plus(SLOT_LENGTH);
            if (isBusinessSlot(slotStart) && busy.stream().noneMatch(i -> overlaps(i, slotStart, slotEnd))) return true;
        }
        return false;
    }

    private boolean available(UUID facilityId, Instant start, Instant end, UUID ownRenewal) {
        Long count = jdbc.queryForObject("select count(*) from renewal_demo_signing_appointments where facility_id=? and status='RESERVED' and renewal_id<>? and appointment_start<? and appointment_end>?",
            Long.class, bytes(facilityId), bytes(ownRenewal), Timestamp.from(end), Timestamp.from(start));
        return count != null && count == 0;
    }

    private static boolean overlaps(Interval interval, Instant start, Instant end) {
        return interval.start().isBefore(end) && interval.end().isAfter(start);
    }

    private static boolean isBusinessSlot(Instant start) {
        return ServiceCalendarPolicy.isAppointmentStart(start);
    }

    private static String reference(UUID facilityId, Instant start) {
        return facilityId + ":" + start.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private record Interval(Instant start, Instant end) {}

    private static byte[] bytes(UUID id) {
        var buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        return buffer.array();
    }

    private static UUID uuid(byte[] value) {
        var buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
