package com.storagehub.api.configuration;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateCentralizedPolicyRequest(
    String policyVersion,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    String changeSummary,

    // Rule 1 & 2: Deposit
    Boolean depositMandatory,
    String depositCalculationMethod,
    @DecimalMin(value = "0.0", message = "depositMonthlyMultiple must be >= 0")
    BigDecimal depositMonthlyMultiple,
    @DecimalMin(value = "0.0", message = "depositFixedAmount must be >= 0")
    BigDecimal depositFixedAmount,
    @DecimalMin(value = "0.0", message = "depositPercentageRate must be >= 0")
    BigDecimal depositPercentageRate,
    @DecimalMin(value = "0.0", message = "minimumDepositAmount must be >= 0")
    BigDecimal minimumDepositAmount,

    // Rule 3: Refund
    String fullRefundConditions,
    String partialRefundConditions,
    String noRefundConditions,
    @Min(value = 1, message = "refundProcessingDays must be >= 1")
    Integer refundProcessingDays,

    // Rule 4 & 8: Renewal
    Boolean allowEarlyRenewal,
    String availableExtensionPeriods,
    @Min(value = 1, message = "renewalAdvanceNoticeDays must be >= 1")
    Integer renewalAdvanceNoticeDays,
    Boolean requireStorageAvailabilityCheck,
    @DecimalMin(value = "0.0", message = "renewalDiscountRate must be >= 0")
    BigDecimal renewalDiscountRate,

    // Rule 5: Early Termination
    Boolean allowEarlyTermination,
    @Min(value = 0, message = "earlyTerminationNoticeDays must be >= 0")
    Integer earlyTerminationNoticeDays,
    @DecimalMin(value = "0.0", message = "earlyTerminationFeeCoefficient must be >= 0")
    BigDecimal earlyTerminationFeeCoefficient,
    @Min(value = 0, message = "earlyTerminationFeeCapMonths must be >= 0")
    Integer earlyTerminationFeeCapMonths,
    @DecimalMin(value = "0.0", message = "earlyTerminationMinFee must be >= 0")
    BigDecimal earlyTerminationMinFee,

    // Rule 6: Late Payment
    @Min(value = 0, message = "gracePeriodDays must be >= 0")
    Integer gracePeriodDays,
    String latePenaltyCalculationMethod,
    @DecimalMin(value = "0.0", message = "lateFeeAmount must be >= 0")
    BigDecimal lateFeeAmount,
    @DecimalMin(value = "0.0", message = "lateFeePercentageRate must be >= 0")
    BigDecimal lateFeePercentageRate,
    @Min(value = 1, message = "maxOverdueGraceDaysBeforeRepossession must be >= 1")
    Integer maxOverdueGraceDaysBeforeRepossession,

    // Rule 7: Deduction
    String deductionCategories,
    Boolean requireEvidenceForDeduction,
    Boolean requireManagerApprovalForDeduction,
    Boolean excludeNormalWearAndTear,

    // Rule 9: Pricing Protection
    Boolean pricingProtectionEnforced,
    String pricingVersion
) {
    public static UpdateCentralizedPolicyRequest ofVersion(String version) {
        return new UpdateCentralizedPolicyRequest(
            version, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null
        );
    }
}
