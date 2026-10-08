package com.storagehub.service;

import com.storagehub.domain.model.Rental;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Read-only owner integration ports. No runtime defaults, policy copies or writes. */
public final class RentalReadSources {
    private RentalReadSources() {}
    public enum BillingMode { PREPAID_FULL_PERIOD, OTHER }
    public enum AccessStatus { ACTIVE, INACTIVE, SUSPENDED, REVOKED, EXPIRED }
    /** Nonnegative totals for all official obligations; security deposit is not a booking/renewal advance. */
    public record Financial(UUID rentalId, Instant checkedAt, String currency,
        BigDecimal outstandingAmount, BigDecimal overdueAmount, BigDecimal securityDepositAmount,
        LocalDate nextDueDate, BillingMode billingMode) {}
    public record Access(UUID rentalId, Instant checkedAt, AccessStatus status) {}
    /** Evidence applies to these exact stored dates, not merely a facility-wide convention. */
    public record Dates(UUID rentalId, LocalDate startDate, LocalDate inclusiveEndDate, String reference) {}
    public interface FinancialSource { Optional<Financial> read(Rental rental); }
    public interface AccessSource { Optional<Access> read(Rental rental); }
    public interface DateSource { Optional<Dates> read(Rental rental); }
}
