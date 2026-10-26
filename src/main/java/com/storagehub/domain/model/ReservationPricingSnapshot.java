package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "reservation_pricing_snapshots")
@Getter
@Setter
@NoArgsConstructor
public class ReservationPricingSnapshot extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false, unique = true)
    private Reservation reservation;

    @Column(nullable = false, length = 50)
    private String pricingPackageCode;

    @Column(nullable = false, length = 50)
    private String pricingPolicyVersion;

    @Column(nullable = false)
    private int rentalMonths;

    /** Immutable setup benefit captured when the reservation is created. */
    @Column(nullable = false)
    private int freeSetupDays;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal grossRentalAmount;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal discountRate = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "voucher_code", length = 50)
    private String voucherCode;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "voucher_discount_type", length = 20)
    private DiscountType voucherDiscountType;

    @Column(name = "voucher_discount_value", precision = 14, scale = 2)
    private BigDecimal voucherDiscountValue;

    @Column(name = "voucher_discount_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal voucherDiscountAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal netRentalAmount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal reservationDepositAmount;

    @Column(name = "early_termination_fee_cap_months", nullable = false)
    private int earlyTerminationFeeCapMonths = 1;

    @Column(name = "early_termination_fee_coefficient", nullable = false, precision = 5, scale = 4)
    private BigDecimal earlyTerminationFeeCoefficient = BigDecimal.ONE;

    @Column(name = "early_termination_notice_days", nullable = false)
    private int earlyTerminationNoticeDays = 14;

    @Column(name = "early_termination_fee", nullable = false, precision = 14, scale = 2)
    private BigDecimal earlyTerminationFee = BigDecimal.ZERO;

    /** Immutable 3/6/12/24-month repricing tiers captured with the reservation. */
    @Column(name = "early_termination_pricing_snapshot_json", nullable = false, columnDefinition = "LONGTEXT")
    private String earlyTerminationPricingSnapshotJson = "[]";

    /** Exact terms shown to and accepted by the customer before payment. */
    @Column(name = "terms_snapshot_json", columnDefinition = "LONGTEXT")
    private String termsSnapshotJson;

    @Column(name = "terms_acceptance_hash", length = 64)
    private String termsAcceptanceHash;

    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "terms_accepted_by")
    private User termsAcceptedBy;

    @Column(name = "early_termination_notice_basis", length = 80)
    private String earlyTerminationNoticeBasis = "REQUESTED_AT_TO_SCHEDULED_DATE";

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal securityDepositAmount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal remainingRentalAmount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal dueAtCheckIn;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalInitialObligation;

    @Column(nullable = false)
    private Instant quotedAt;

    @Column(nullable = false)
    private Instant expiresAt;
}
