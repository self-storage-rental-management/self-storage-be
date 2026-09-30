package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "reservations")
@Getter
@Setter
@NoArgsConstructor
public class Reservation extends BaseEntity {

    @Column(nullable = false, unique = true, length = 32)
    private String reservationCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facility_id", nullable = false)
    private Facility facility;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_type_id", nullable = false)
    private UnitType unitType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_unit_id")
    private StorageUnit assignedUnit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReservationStatus status = ReservationStatus.AWAITING_EMAIL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GoodsReviewStatus goodsReviewStatus = GoodsReviewStatus.NOT_REQUIRED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "goods_reviewed_by")
    private User goodsReviewedBy;

    @Column
    private Instant goodsReviewSubmittedAt;

    @Column
    private Instant goodsReviewDueAt;

    @Column
    private Instant goodsReviewedAt;

    @Column(length = 1000)
    private String goodsReviewNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CompatibilityResult compatibilityResult = CompatibilityResult.PENDING;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(length = 500)
    private String goodsCondition;

    @Column(nullable = false, precision = 16, scale = 6)
    private BigDecimal totalGoodsVolumeM3 = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalGoodsWeightKg = BigDecimal.ZERO;

    @Column
    private Instant holdExpiresAt;

    @Column
    private Instant paymentExpiresAt;

    @Column
    private Instant confirmedAt;

    @Column
    private Instant depositPaidAt;

    @Column
    private Instant cancelledAt;

    @Column
    private Instant expiredAt;

    @Column
    private Instant rejectedAt;

    @Column(length = 1000)
    private String cancelReason;

    @Column(length = 1000)
    private String rejectionReason;

    @Column(length = 2000)
    private String notes;

    @Version
    private long version;
}
