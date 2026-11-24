package com.storagehub.api.configuration;

import com.storagehub.domain.model.PublicationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CentralizedPolicyResponse(
    UUID id,
    String policyVersion,
    PublicationStatus publicationStatus,
    String runtimeStatus,
    Instant publishedAt,
    UUID publishedById,
    String publishedByName,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    boolean active,
    String changeSummary,

    // Rule 1 & 2: Deposit
    boolean depositMandatory,
    String depositCalculationMethod,
    BigDecimal depositMonthlyMultiple,
    BigDecimal depositFixedAmount,
    BigDecimal depositPercentageRate,
    BigDecimal minimumDepositAmount,

    // Rule 3: Refund
    String fullRefundConditions,
    String partialRefundConditions,
    String noRefundConditions,
    int refundProcessingDays,

    // Rule 4 & 8: Renewal
    boolean allowEarlyRenewal,
    List<Integer> availableExtensionPeriods,
    int renewalAdvanceNoticeDays,
    boolean requireStorageAvailabilityCheck,
    BigDecimal renewalDiscountRate,

    // Rule 5: Early Termination
    boolean allowEarlyTermination,
    int earlyTerminationNoticeDays,
    BigDecimal earlyTerminationFeeCoefficient,
    int earlyTerminationFeeCapMonths,
    BigDecimal earlyTerminationMinFee,

    // Rule 6: Late Payment
    int gracePeriodDays,
    String latePenaltyCalculationMethod,
    BigDecimal lateFeeAmount,
    BigDecimal lateFeePercentageRate,
    int maxOverdueGraceDaysBeforeRepossession,

    // Rule 7: Deduction
    String deductionCategories,
    boolean requireEvidenceForDeduction,
    boolean requireManagerApprovalForDeduction,
    boolean excludeNormalWearAndTear,

    // Rule 9: Pricing Protection
    boolean pricingProtectionEnforced,
    String pricingVersion,

    Instant createdAt,
    Instant updatedAt
) {}
