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
    private Instant appointmentAt;
    private Instant createdAt;
    private UUID assignedUnitId;
    private String assignedUnitCode;
    private String assignedUnitStatus;
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    private String customerAddress;
    private String identityDocumentNumber;

    // Snapshot discount fields (bảo toàn lịch sử mã giảm giá trên hóa đơn / đơn đặt)
    private String pricingPackageCode;
    private int rentalMonths;
    private int freeSetupDays;
    private boolean setupExitReviewRequired;
    private java.time.Instant setupGoodsRevisionUsedAt;
    private BigDecimal monthlyPrice;
    private BigDecimal grossRentalAmount;
    private BigDecimal discountRate;
    private BigDecimal discountAmount;
    private int earlyTerminationFeeCapMonths = 1;
    private BigDecimal earlyTerminationFeeCoefficient = BigDecimal.ONE;
    private int earlyTerminationNoticeDays = 14;
    private BigDecimal earlyTerminationFee = BigDecimal.ZERO;
    private String voucherCode;
    private BigDecimal voucherDiscountAmount;

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
             null, 0, totalRentalAmount, BigDecimal.ZERO, BigDecimal.ZERO, null);
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
        this(id, reservationCode, quoteId, facilityId, unitTypeId, status, goodsReviewStatus,
             compatibilityResult, startDate, endDate, totalRentalAmount, reservationDepositAmount,
             securityDepositAmount, remainingRentalAmount, dueAtCheckIn, totalInitialObligation,
             totalGoodsVolumeM3, totalGoodsWeightKg, holdExpiresAt, createdAt, pricingPackageCode,
             rentalMonths, grossRentalAmount, discountRate, discountAmount, null);
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
                               BigDecimal discountRate, BigDecimal discountAmount, Instant appointmentAt) {
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
        this.appointmentAt = appointmentAt;
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
    public Instant getAppointmentAt() { return appointmentAt; }
    public UUID getAssignedUnitId() { return assignedUnitId; }
    public void setAssignedUnitId(UUID assignedUnitId) { this.assignedUnitId = assignedUnitId; }
    public String getAssignedUnitCode() { return assignedUnitCode; }
    public void setAssignedUnitCode(String assignedUnitCode) { this.assignedUnitCode = assignedUnitCode; }
    public String getAssignedUnitStatus() { return assignedUnitStatus; }
    public void setAssignedUnitStatus(String assignedUnitStatus) { this.assignedUnitStatus = assignedUnitStatus; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }
    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }
    public String getCustomerAddress() { return customerAddress; }
    public void setCustomerAddress(String customerAddress) { this.customerAddress = customerAddress; }
    public String getIdentityDocumentNumber() { return identityDocumentNumber; }
    public void setIdentityDocumentNumber(String identityDocumentNumber) { this.identityDocumentNumber = identityDocumentNumber; }

    public String getPricingPackageCode() { return pricingPackageCode; }
    public int getRentalMonths() { return rentalMonths; }
    public int getFreeSetupDays() { return freeSetupDays; }
    public void setFreeSetupDays(int freeSetupDays) { this.freeSetupDays = freeSetupDays; }
    public boolean isSetupExitReviewRequired() { return setupExitReviewRequired; }
    public void setSetupExitReviewRequired(boolean setupExitReviewRequired) { this.setupExitReviewRequired = setupExitReviewRequired; }
    public java.time.Instant getSetupGoodsRevisionUsedAt() { return setupGoodsRevisionUsedAt; }
    public void setSetupGoodsRevisionUsedAt(java.time.Instant value) { this.setupGoodsRevisionUsedAt = value; }
    public BigDecimal getMonthlyPrice() { return monthlyPrice; }
    public void setMonthlyPrice(BigDecimal monthlyPrice) { this.monthlyPrice = monthlyPrice; }
    public BigDecimal getGrossRentalAmount() { return grossRentalAmount; }
    public BigDecimal getDiscountRate() { return discountRate; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public String getVoucherCode() { return voucherCode; }
    public void setVoucherCode(String voucherCode) { this.voucherCode = voucherCode; }
    public BigDecimal getVoucherDiscountAmount() { return voucherDiscountAmount; }
    public void setVoucherDiscountAmount(BigDecimal voucherDiscountAmount) { this.voucherDiscountAmount = voucherDiscountAmount; }
    public int getEarlyTerminationFeeCapMonths() { return earlyTerminationFeeCapMonths; }
    public void setEarlyTerminationFeeCapMonths(int value) { this.earlyTerminationFeeCapMonths = value; }
    public BigDecimal getEarlyTerminationFeeCoefficient() { return earlyTerminationFeeCoefficient; }
    public void setEarlyTerminationFeeCoefficient(BigDecimal value) { this.earlyTerminationFeeCoefficient = value; }
    public int getEarlyTerminationNoticeDays() { return earlyTerminationNoticeDays; }
    public void setEarlyTerminationNoticeDays(int value) { this.earlyTerminationNoticeDays = value; }
    public BigDecimal getEarlyTerminationFee() { return earlyTerminationFee; }
    public void setEarlyTerminationFee(BigDecimal value) { this.earlyTerminationFee = value; }
}
