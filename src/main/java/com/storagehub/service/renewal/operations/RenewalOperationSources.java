package com.storagehub.service.renewal.operations;

import com.storagehub.api.renewal.RenewalQuoteResponse.Terms;
import com.storagehub.domain.model.Renewal;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Owner-supplied integration contracts. No runtime mocks/default BO policy are installed. */
public final class RenewalOperationSources {
    private RenewalOperationSources() {}
    public enum Capability { READ, ARRIVAL, INCIDENT, CASH, COMPLETE, EXCEPTION, REFUND }
    public interface AuthorizationSource {
        /** Checks actual permission, current Staff assignment and evidence visibility, not just role. */
        void require(ActorPrincipal actor, Renewal renewal, Capability capability);
        /** Authoritative assignment+permission set, used BEFORE pagination. Missing contract is not an empty queue. */
        Set<UUID> assignedRenewalIds(ActorPrincipal actor);
    }
    public record Policy(String reference, String version, Duration signingWindow, Duration exceptionExtensionLimit) {}
    public interface PolicySource {
        Optional<Policy> read(Renewal renewal);
        /** Authoritative facility-fault classification/review; an incident note alone is not approval evidence. */
        void requireFacilityFault(Renewal renewal, UUID incidentId, UUID reviewerId);
    }
    public record Slot(String reference, Instant start, Instant end) {}
    public interface CalendarSource {
        default boolean atomic() { return false; }
        /** Actual service slot, including office hours/holidays/duration; empty means no available slot. */
        Optional<Slot> slot(Renewal renewal, Instant start);
        boolean feasible(Renewal renewal, Instant start, Instant deadline);
        /** Atomically reserves the signing slot and replaces the previous reservation without altering Check-in. */
        void reserve(Renewal renewal, Slot slot, Instant now);
    }
    public interface SafetySource {
        default boolean atomic() { return false; }
        /** Same transaction/lock protocol as Booking, Return, Assignment, Recovery and Payment writers. */
        Instant check(Renewal renewal, Terms acceptedTerms, UUID holdRef, Instant now);
        void retain(Renewal renewal, UUID holdRef, Instant deadline);
        void consume(Renewal renewal, UUID holdRef);
        void release(Renewal renewal, UUID holdRef);
    }
    public enum Outcome { SUCCESS, FAILED, NOT_RECEIVED }
    public record Deposit(UUID paymentRef, UUID renewalId, Outcome outcome, BigDecimal amount, String currency, Instant paidAt) {}
    public record Statement(UUID reference, UUID renewalId, String version, Instant expiresAt,
        BigDecimal amount, String currency, List<UUID> requiredObligations) {}
    public record Receipt(UUID reference, UUID renewalId, UUID statementRef, BigDecimal amount, String currency, Instant receivedAt) {}
    public interface AccountingSource {
        default boolean atomic() { return false; }
        /** Shared engine persists attempt, verifies server amount, allocations and resource-bound idempotency. */
        Deposit deposit(Renewal renewal, Terms terms, UUID actorId, String key, Instant deadline);
        /** Reconciles late/in-flight attempts before expiry; empty/incomplete must never mean zero paid. */
        boolean safeToExpire(Renewal renewal);
        Statement statement(Renewal renewal, Terms terms, Instant now);
        Receipt cash(Renewal renewal, UUID statementRef, String receiptReference, UUID actorId, String key, Instant now);
        /** Locks/rechecks the policy-required obligation set, deposits, CASH allocations and disputes. */
        void requireFullyPaid(Renewal renewal, Terms terms, Instant now);
    }
    public interface EvidenceSource {
        void require(ActorPrincipal actor, Renewal renewal, String purpose, List<UUID> fileIds);
    }
    public record RefundReservation(UUID reference, UUID renewalId, BigDecimal amount, String currency,
        Instant reviewDueAt, Instant executionDueAt) {}
    public interface RefundSource {
        default boolean atomic() { return false; }
        /** Computes policy entitlement from actual paid less actual/pending refunds; reserves once. Not payout. */
        RefundReservation reserve(Renewal renewal, UUID incidentId, UUID approverId, String key, Instant now);
    }
}
