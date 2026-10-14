package com.storagehub.service.overdue;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Rental;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local-demo Recovery authority. This covers Recovery cases created by this application only. */
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("!${storagehub.integration.recovery-tasks.enabled:false} && (${storagehub.integration.local-demo-operational-sources.enabled:false} || ${storagehub.integration.local-demo-recovery.enabled:false})")
@Transactional(propagation = Propagation.MANDATORY)
public class LocalDemoRecoverySource implements OverdueSources.RecoverySource {
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
    public java.util.Optional<OverdueSources.RecoveryState> state(Rental rental, Instant now) {
        requireContext(rental, now);
        // Writable decisions use current reads; projections remain non-locking.
        boolean locking = TransactionSynchronizationManager.isActualTransactionActive()
            && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        List<RecoveryRow> states = jdbc.query(
            "select facility_id,status from renewal_demo_recovery_cases where rental_id=? order by received_at desc" + (locking ? " for update" : ""),
            (rs, row) -> new RecoveryRow(uuid(rs.getBytes("facility_id")), rs.getString("status")), bytes(rental.getId()));
        if (states.stream().anyMatch(row -> !row.facilityId().equals(rental.getFacility().getId()))) {
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Recovery record belongs to another facility");
        }
        boolean blocked = states.stream().anyMatch(row -> "ACTIVE".equals(row.status()));
        return java.util.Optional.of(new OverdueSources.RecoveryState(
            rental.getId(), rental.getFacility().getId(), now, blocked));
    }

    @Override
    public OverdueSources.Handoff receive(Rental rental, String caseRef, String reason, String key, Instant now) {
        requireContext(rental, now);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Recovery handoff requires a writable transaction");
        }
        if (caseRef == null || caseRef.isBlank() || reason == null || reason.isBlank() || key == null || key.isBlank()) {
            throw ApiExceptions.conflict("Recovery handoff request is incomplete");
        }
        Rental locked = em.find(Rental.class, rental.getId(), LockModeType.PESSIMISTIC_WRITE);
        if (locked == null) throw ApiExceptions.notFound("Recovery rental not found");
        em.refresh(locked, LockModeType.PESSIMISTIC_WRITE);
        if (locked.getFacility() == null || !locked.getFacility().getId().equals(rental.getFacility().getId())) {
            throw ApiExceptions.conflict("Recovery rental facility changed");
        }

        var existing = jdbc.query(
            "select id,rental_id,facility_id,status,reason from renewal_demo_recovery_cases where case_ref=? for update",
            (rs, row) -> new Existing(uuid(rs.getBytes("id")), uuid(rs.getBytes("rental_id")),
                uuid(rs.getBytes("facility_id")), rs.getString("status"), rs.getString("reason")), caseRef);
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            if (!row.rentalId().equals(rental.getId()) || !row.facilityId().equals(rental.getFacility().getId())
                || !row.reason().equals(reason)) {
                throw ApiExceptions.conflict("Recovery retry differs from the accepted handoff");
            }
            return new OverdueSources.Handoff(row.id(), "ALREADY_RECEIVED");
        }

        var active = jdbc.query(
            "select id from renewal_demo_recovery_cases where rental_id=? and status='ACTIVE' for update",
            (rs, row) -> uuid(rs.getBytes("id")), bytes(rental.getId()));
        if (!active.isEmpty()) {
            return new OverdueSources.Handoff(active.getFirst(), "ALREADY_RECEIVED");
        }

        UUID id = UUID.randomUUID();
        jdbc.update("insert into renewal_demo_recovery_cases(id,case_ref,rental_id,facility_id,status,reason,received_at) values(?,?,?,?,?,?,?)",
            bytes(id), caseRef, bytes(rental.getId()), bytes(rental.getFacility().getId()), "ACTIVE", reason,
            Timestamp.from(now));
        return new OverdueSources.Handoff(id, "RECEIVED");
    }

    private static void requireContext(Rental rental, Instant now) {
        if (rental == null || rental.getId() == null || rental.getFacility() == null
            || rental.getFacility().getId() == null || now == null) {
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Recovery rental context is incomplete");
        }
    }

    private record Existing(UUID id, UUID rentalId, UUID facilityId, String status, String reason) {}
    private record RecoveryRow(UUID facilityId, String status) {}

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
