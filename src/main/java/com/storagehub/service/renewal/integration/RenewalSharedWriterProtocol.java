package com.storagehub.service.renewal.integration;

import com.storagehub.domain.model.Rental;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Owner integration boundary, NOT implemented by a flag or the local operational guard.
 * Implement only once Booking/Assignment/Return/Maintenance/Recovery/Payment writers use
 * the same lock order and consult extension holds. Caller already locks Rental then Unit.
 * Must re-read current Recovery, financial coverage and interval capacity in this transaction
 * using current/locking reads (including callers running at REPEATABLE_READ).
 */
public interface RenewalSharedWriterProtocol {
    default boolean ready() { return false; }
    /** Returns verified current recovery cutoff; missing coverage must throw DEFERRED_SOURCE. */
    Instant lockAndCheck(Rental rental,LocalDate start,LocalDate exclusiveEnd,Instant now);
}
