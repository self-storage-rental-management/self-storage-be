package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "centralized_business_policies")
@Getter
@Setter
@NoArgsConstructor
public class CentralizedPolicy extends BaseEntity {

    @Column(name = "policy_version", nullable = false, unique = true, length = 50)
    private String policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "publication_status", nullable = false, length = 20)
    private PublicationStatus publicationStatus = PublicationStatus.DRAFT;

    @Column(name = "published_at")
    private Instant publishedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "published_by")
    private User publishedBy;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(nullable = false)
    private boolean active = false;

    @Column(name = "change_summary", columnDefinition = "TEXT")
    private String changeSummary;

    // ==========================================
    // RULE 1 & 2: Mandatory Deposit & Calculation
    // ==========================================
    @Column(name = "deposit_mandatory", nullable = false)
    private boolean depositMandatory = true;

    @Column(name = "deposit_calculation_method", nullable = false, length = 32)
    private String depositCalculationMethod = "MONTHLY_RENT_MULTIPLE";

    @Column(name = "deposit_monthly_multiple", nullable = false, precision = 5, scale = 2)
    private BigDecimal depositMonthlyMultiple = BigDecimal.valueOf(1.00);

    @Column(name = "deposit_fixed_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal depositFixedAmount = new BigDecimal("2000000.00");

    @Column(name = "deposit_percentage_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal depositPercentageRate = new BigDecimal("0.2000");

    @Column(name = "minimum_deposit_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal minimumDepositAmount = new BigDecimal("500000.00");

    // ==========================================
    // RULE 3: Refund Conditions
    // ==========================================
    @Column(name = "full_refund_conditions", columnDefinition = "TEXT")
    private String fullRefundConditions;

    @Column(name = "partial_refund_conditions", columnDefinition = "TEXT")
    private String partialRefundConditions;

    @Column(name = "no_refund_conditions", columnDefinition = "TEXT")
    private String noRefundConditions;

    @Column(name = "refund_processing_days", nullable = false)
    private int refundProcessingDays = 7;

    // ==========================================
    // RULE 4 & 8: Early Rental Renewal / Extension
    // ==========================================
    @Column(name = "allow_early_renewal", nullable = false)
    private boolean allowEarlyRenewal = true;

    @Column(name = "available_extension_periods", nullable = false, length = 100)
    private String availableExtensionPeriods = "1,3,6,12,24";

    @Column(name = "renewal_advance_notice_days", nullable = false)
    private int renewalAdvanceNoticeDays = 15;

    @Column(name = "require_storage_availability_check", nullable = false)
    private boolean requireStorageAvailabilityCheck = true;

    @Column(name = "renewal_discount_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal renewalDiscountRate = new BigDecimal("0.0500");

    // ==========================================
    // RULE 5: Early Contract Termination
    // ==========================================
    @Column(name = "allow_early_termination", nullable = false)
    private boolean allowEarlyTermination = true;

    @Column(name = "early_termination_notice_days", nullable = false)
    private int earlyTerminationNoticeDays = 14;

    @Column(name = "early_termination_fee_coefficient", nullable = false, precision = 5, scale = 4)
    private BigDecimal earlyTerminationFeeCoefficient = BigDecimal.ONE;

    @Column(name = "early_termination_fee_cap_months", nullable = false)
    private int earlyTerminationFeeCapMonths = 1;

    @Column(name = "early_termination_min_fee", nullable = false, precision = 14, scale = 2)
    private BigDecimal earlyTerminationMinFee = new BigDecimal("500000.00");

    // ==========================================
    // RULE 6: Late Payment Penalty
    // ==========================================
    @Column(name = "grace_period_days", nullable = false)
    private int gracePeriodDays = 3;

    @Column(name = "late_penalty_calculation_method", nullable = false, length = 32)
    private String latePenaltyCalculationMethod = "FIXED_DAILY_AMOUNT";

    @Column(name = "late_fee_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal lateFeeAmount = new BigDecimal("50000.00");

    @Column(name = "late_fee_percentage_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal lateFeePercentageRate = new BigDecimal("0.0050");

    @Column(name = "max_overdue_grace_days_before_repossession", nullable = false)
    private int maxOverdueGraceDaysBeforeRepossession = 7;

    // ==========================================
    // RULE 7: Security Deposit Deduction
    // ==========================================
    @Column(name = "deduction_categories", columnDefinition = "TEXT")
    private String deductionCategories;

    @Column(name = "require_evidence_for_deduction", nullable = false)
    private boolean requireEvidenceForDeduction = true;

    @Column(name = "require_manager_approval_for_deduction", nullable = false)
    private boolean requireManagerApprovalForDeduction = true;

    @Column(name = "exclude_normal_wear_and_tear", nullable = false)
    private boolean excludeNormalWearAndTear = true;

    // ==========================================
    // RULE 9: Pricing Protection
    // ==========================================
    @Column(name = "pricing_protection_enforced", nullable = false)
    private boolean pricingProtectionEnforced = true;

    @Column(name = "pricing_version", nullable = false, length = 50)
    private String pricingVersion = "PRICE-2026.1";
}
