package com.storagehub.service.renewal;

import com.storagehub.domain.model.Rental;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Integration ports, not Manager-owned copies of BO policies. Implementations must use authoritative shared sources. */
public final class RenewalSources {
    private RenewalSources() {}
    public record Policy(String reference, String version, Duration quoteTtl, Duration paymentWindow,
        int requestWindowDays, BigDecimal depositRate, Set<UUID> eligiblePackageIds) {}
    public record Eligibility(boolean datesVerified, boolean recoveryBlocked, boolean feasible,
        Instant recoveryCutoff) {}
    public record Financial(Instant checkedAt, List<UUID> dueObligations, boolean unresolvedDispute) {}
    public interface PolicySource { Optional<Policy> read(Rental rental, LocalDate extensionStart); }
    public interface EligibilitySource { Optional<Eligibility> read(Rental rental, Instant now); }
    public interface FinancialSource { Optional<Financial> read(Rental rental); }
    public record Price(UUID packageId,String packageCode,String packageVersion,UUID unitTypeId,
        int months,BigDecimal monthlyPrice,BigDecimal discountRate,String currency,int moneyScale) {}
    /** Authoritative pricing + agreed currency/rounding. No adapter is provided until the owner publishes it. */
    public interface PricingSource { Optional<List<Price>> read(Rental rental,LocalDate extensionStart); }
    /** Must participate in shared booking/assignment/maintenance lock protocol; never an in-memory reservation. */
    public interface ExtensionHoldSource {
        /** Must guarantee rollback together with the caller transaction, not a remote non-atomic side effect. */
        default boolean participatesInTransaction() { return false; }
        UUID acquire(Rental rental, UUID renewalId, LocalDate start, LocalDate exclusiveEnd, Instant expiresAt);
    }
}
