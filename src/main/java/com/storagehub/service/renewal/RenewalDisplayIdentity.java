package com.storagehub.service.renewal;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Renewal;
import com.storagehub.domain.model.RenewalDisplaySequence;
import com.storagehub.domain.model.RenewalStatus;
import com.storagehub.domain.repo.RenewalRepository;
import com.storagehub.service.BusinessRecordCodes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Public renewal identity: one rental code plus a human-readable occurrence number. */
@Service
@RequiredArgsConstructor
public class RenewalDisplayIdentity {
    private final RenewalRepository renewals;
    private final BusinessRecordCodes codes;
    private final EntityManager entityManager;
    private static final Set<RenewalStatus> UNNUMBERED_TERMINAL_STATUSES = Set.of(
        RenewalStatus.rejected, RenewalStatus.cancelled, RenewalStatus.payment_expired
    );

    @Transactional(readOnly = true)
    public Identity resolve(Renewal renewal) {
        if (renewal == null || renewal.getId() == null || renewal.getRental() == null || renewal.getRental().getId() == null) {
            throw ApiExceptions.conflict("Renewal identity is incomplete");
        }
        if (renewal.getStatus() == null) throw ApiExceptions.conflict("Renewal identity status is incomplete");
        var rentalId = renewal.getRental().getId();
        Integer sequence = renewal.getDisplaySequence();
        if (sequence == null && !UNNUMBERED_TERMINAL_STATUSES.contains(renewal.getStatus())) {
            sequence = nextProspectiveSequence(rentalId);
        }
        return new Identity(codes.find("TH:" + rentalId), sequence);
    }

    @Transactional(readOnly = true)
    public Optional<Identity> find(UUID renewalId) {
        return renewalId == null ? Optional.empty() : renewals.findById(renewalId).map(this::resolve);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int assignCompletedSequence(Renewal renewal) {
        validate(renewal);
        if (renewal.getStatus() != RenewalStatus.completed) {
            throw ApiExceptions.conflict("Only a completed Renewal can receive an occurrence number");
        }
        if (renewal.getDisplaySequence() != null) return renewal.getDisplaySequence();

        var rental = renewal.getRental();
        var rentalId = rental.getId();
        entityManager.lock(rental, LockModeType.PESSIMISTIC_WRITE);
        if (renewal.getDisplaySequence() != null) return renewal.getDisplaySequence();

        int persistedMaximum = Optional.ofNullable(renewals.findMaxDisplaySequence(rentalId)).orElse(0);
        var counter = entityManager.find(RenewalDisplaySequence.class, rentalId, LockModeType.PESSIMISTIC_WRITE);
        int last = counter == null ? persistedMaximum : Math.max(counter.getLastSequence(), persistedMaximum);
        final int next;
        try {
            next = Math.addExact(last, 1);
        } catch (ArithmeticException error) {
            throw ApiExceptions.conflict("Renewal occurrence sequence is exhausted");
        }
        if (counter == null) entityManager.persist(new RenewalDisplaySequence(rentalId, next));
        else counter.advanceTo(next);
        renewal.setDisplaySequence(next);
        return next;
    }

    private int nextProspectiveSequence(UUID rentalId) {
        int persistedMaximum = Optional.ofNullable(renewals.findMaxDisplaySequence(rentalId)).orElse(0);
        var counter = entityManager.find(RenewalDisplaySequence.class, rentalId);
        int last = counter == null ? persistedMaximum : Math.max(counter.getLastSequence(), persistedMaximum);
        try {
            return Math.addExact(last, 1);
        } catch (ArithmeticException error) {
            throw ApiExceptions.conflict("Renewal occurrence sequence is exhausted");
        }
    }

    private static void validate(Renewal renewal) {
        if (renewal == null || renewal.getId() == null || renewal.getRental() == null || renewal.getRental().getId() == null) {
            throw ApiExceptions.conflict("Renewal identity is incomplete");
        }
    }

    public record Identity(String rentalDisplayCode, Integer renewalSequence) {
        public String label() {
            String parent = rentalDisplayCode == null ? "Hồ sơ thuê" : rentalDisplayCode;
            return renewalSequence == null ? parent + " · Gia hạn" : parent + " · Gia hạn lần " + renewalSequence;
        }
    }
}
