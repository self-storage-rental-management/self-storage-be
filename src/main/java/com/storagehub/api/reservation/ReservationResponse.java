package com.storagehub.api.reservation;

import com.storagehub.domain.model.CompatibilityResult;
import com.storagehub.domain.model.GoodsReviewStatus;
import com.storagehub.domain.model.ReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class ReservationResponse {

    private UUID id;
    private String reservationCode;
    private UUID quoteId;
    private UUID facilityId;
    private UUID unitTypeId;
    private ReservationStatus status;
    private GoodsReviewStatus goodsReviewStatus;
    private CompatibilityResult compatibilityResult;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal totalRentalAmount;
    private BigDecimal reservationDepositAmount;
    private BigDecimal securityDepositAmount;
    private BigDecimal remainingRentalAmount;
    private BigDecimal dueAtCheckIn;
    private BigDecimal totalInitialObligation;
    private BigDecimal totalGoodsVolumeM3;
    private BigDecimal totalGoodsWeightKg;
    private Instant holdExpiresAt;
    private Instant createdAt;

    // Snapshot discount fields (bảo toàn lịch sử mã giảm giá trên hóa đơn / đơn đặt)
    private String pricingPackageCode;
    private int rentalMonths;
    private BigDecimal grossRentalAmount;
    private BigDecimal discountRate;
    private BigDecimal discountAmount;

    public ReservationResponse() {
    }

    public ReservationResponse(UUID id, String reservationCode, UUID quoteId, UUID facilityId,
                               UUID unitTypeId, ReservationStatus status,
                               GoodsReviewStatus goodsReviewStatus,
                               CompatibilityResult compatibilityResult, LocalDate startDate,
                               LocalDate endDate, BigDecimal totalRentalAmount,
                               BigDecimal reservationDepositAmount, BigDecimal securityDepositAmount,
                               BigDecimal remainingRentalAmount, BigDecimal dueAtCheckIn,
                               BigDecimal totalInitialObligation, BigDecimal totalGoodsVolumeM3,
                               BigDecimal totalGoodsWeightKg, Instant holdExpiresAt, Instant createdAt) {
        this(id, reservationCode, quoteId, facilityId, unitTypeId, status, goodsReviewStatus,
             compatibilityResult, startDate, endDate, totalRentalAmount, reservationDepositAmount,
             securityDepositAmount, remainingRentalAmount, dueAtCheckIn, totalInitialObligation,
             totalGoodsVolumeM3, totalGoodsWeightKg, holdExpiresAt, createdAt,
             null, 0, totalRentalAmount, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public ReservationResponse(UUID id, String reservationCode, UUID quoteId, UUID facilityId,
                               UUID unitTypeId, ReservationStatus status,
                               GoodsReviewStatus goodsReviewStatus,
                               CompatibilityResult compatibilityResult, LocalDate startDate,
                               LocalDate endDate, BigDecimal totalRentalAmount,
                               BigDecimal reservationDepositAmount, BigDecimal securityDepositAmount,
                               BigDecimal remainingRentalAmount, BigDecimal dueAtCheckIn,
                               BigDecimal totalInitialObligation, BigDecimal totalGoodsVolumeM3,
                               BigDecimal totalGoodsWeightKg, Instant holdExpiresAt, Instant createdAt,
                               String pricingPackageCode, int rentalMonths, BigDecimal grossRentalAmount,
                               BigDecimal discountRate, BigDecimal discountAmount) {
        this.id = id;
        this.reservationCode = reservationCode;
        this.quoteId = quoteId;
        this.facilityId = facilityId;
        this.unitTypeId = unitTypeId;
        this.status = status;
        this.goodsReviewStatus = goodsReviewStatus;
        this.compatibilityResult = compatibilityResult;
        this.startDate = startDate;
        this.endDate = endDate;
        this.totalRentalAmount = totalRentalAmount;
        this.reservationDepositAmount = reservationDepositAmount;
        this.securityDepositAmount = securityDepositAmount;
        this.remainingRentalAmount = remainingRentalAmount;
        this.dueAtCheckIn = dueAtCheckIn;
        this.totalInitialObligation = totalInitialObligation;
        this.totalGoodsVolumeM3 = totalGoodsVolumeM3;
        this.totalGoodsWeightKg = totalGoodsWeightKg;
        this.holdExpiresAt = holdExpiresAt;
        this.createdAt = createdAt;
        this.pricingPackageCode = pricingPackageCode;
        this.rentalMonths = rentalMonths;
        this.grossRentalAmount = grossRentalAmount;
        this.discountRate = discountRate;
        this.discountAmount = discountAmount;
    }

    public UUID getId() { return id; }
    public String getReservationCode() { return reservationCode; }
    public UUID getQuoteId() { return quoteId; }
    public UUID getFacilityId() { return facilityId; }
    public UUID getUnitTypeId() { return unitTypeId; }
    public ReservationStatus getStatus() { return status; }
    public GoodsReviewStatus getGoodsReviewStatus() { return goodsReviewStatus; }
    public CompatibilityResult getCompatibilityResult() { return compatibilityResult; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public BigDecimal getTotalRentalAmount() { return totalRentalAmount; }
    public BigDecimal getReservationDepositAmount() { return reservationDepositAmount; }
    public BigDecimal getSecurityDepositAmount() { return securityDepositAmount; }
    public BigDecimal getRemainingRentalAmount() { return remainingRentalAmount; }
    public BigDecimal getDueAtCheckIn() { return dueAtCheckIn; }
    public BigDecimal getTotalInitialObligation() { return totalInitialObligation; }
    public BigDecimal getTotalGoodsVolumeM3() { return totalGoodsVolumeM3; }
    public BigDecimal getTotalGoodsWeightKg() { return totalGoodsWeightKg; }
    public Instant getHoldExpiresAt() { return holdExpiresAt; }
    public Instant getCreatedAt() { return createdAt; }

    public String getPricingPackageCode() { return pricingPackageCode; }
    public int getRentalMonths() { return rentalMonths; }
    public BigDecimal getGrossRentalAmount() { return grossRentalAmount; }
    public BigDecimal getDiscountRate() { return discountRate; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
}
