package com.storagehub.api.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

public class ReservationQuoteResponse {

    private String setupEarlyExitPolicyVersion;
    private BigDecimal setupEarlyExitFee;
    public String getSetupEarlyExitPolicyVersion() { return setupEarlyExitPolicyVersion; }
    public void setSetupEarlyExitPolicyVersion(String value) { setupEarlyExitPolicyVersion = value; }
    public BigDecimal getSetupEarlyExitFee() { return setupEarlyExitFee; }
    public void setSetupEarlyExitFee(BigDecimal value) { setupEarlyExitFee = value; }

    private UUID quoteId;
    private UUID facilityId;
    private String facilityName;
    private UUID unitTypeId;
    private String unitTypeName;
    private LocalDate startDate;
    private LocalDate endDate;
    private int rentalMonths;
    private int freeSetupDays;
    private BigDecimal monthlyPrice;
    private BigDecimal subtotal;
    private String pricingPackageCode;
    private BigDecimal discountRate;
    private BigDecimal discountAmount;
    private String voucherCode;
    private BigDecimal voucherDiscountAmount = BigDecimal.ZERO;
    private BigDecimal totalAfterDiscount;
    private BigDecimal reservationDepositRate = new BigDecimal("0.20");
    private BigDecimal reservationDepositAmount;
    private int earlyTerminationFeeCapMonths = 1;
    private BigDecimal earlyTerminationFeeCoefficient = BigDecimal.ONE;
    private int earlyTerminationNoticeDays = 14;
    private BigDecimal earlyTerminationFee = BigDecimal.ZERO;
    private BigDecimal securityDepositAmount;
    private BigDecimal remainingRentalAmount;
    private BigDecimal dueAtCheckIn;
    private BigDecimal totalInitialObligation;
    private String policyVersion;
    private Instant quotedAt;
    private Instant expiresAt;
    private List<EarlyTerminationTier> earlyTerminationTiers = List.of();
    private String earlyTerminationNoticeBasis = "REQUESTED_AT_TO_SCHEDULED_DATE";

    public record EarlyTerminationTier(int rentalMonths, BigDecimal monthlyPrice, int freeSetupDays) {}

    public ReservationQuoteResponse() {
    }

    public ReservationQuoteResponse(UUID quoteId, UUID facilityId, UUID unitTypeId,
                                    LocalDate startDate, LocalDate endDate, int rentalMonths,
                                    BigDecimal monthlyPrice, BigDecimal subtotal,
                                    BigDecimal discountRate, BigDecimal discountAmount,
                                    BigDecimal totalAfterDiscount, BigDecimal reservationDepositAmount,
                                    BigDecimal securityDepositAmount, BigDecimal remainingRentalAmount,
                                    BigDecimal dueAtCheckIn, BigDecimal totalInitialObligation,
                                    String policyVersion, Instant quotedAt, Instant expiresAt) {
        this(quoteId, facilityId, null, unitTypeId, null, startDate, endDate, rentalMonths, monthlyPrice, subtotal,
             null, discountRate, discountAmount, null, BigDecimal.ZERO, totalAfterDiscount,
             reservationDepositAmount, securityDepositAmount, remainingRentalAmount, dueAtCheckIn,
             totalInitialObligation, policyVersion, quotedAt, expiresAt);
    }

    public ReservationQuoteResponse(UUID quoteId, UUID facilityId, UUID unitTypeId,
                                    LocalDate startDate, LocalDate endDate, int rentalMonths,
                                    BigDecimal monthlyPrice, BigDecimal subtotal,
                                    String pricingPackageCode,
                                    BigDecimal discountRate, BigDecimal discountAmount,
                                    BigDecimal totalAfterDiscount, BigDecimal reservationDepositAmount,
                                    BigDecimal securityDepositAmount, BigDecimal remainingRentalAmount,
                                    BigDecimal dueAtCheckIn, BigDecimal totalInitialObligation,
                                    String policyVersion, Instant quotedAt, Instant expiresAt) {
        this(quoteId, facilityId, null, unitTypeId, null, startDate, endDate, rentalMonths, monthlyPrice, subtotal,
             pricingPackageCode, discountRate, discountAmount, null, BigDecimal.ZERO, totalAfterDiscount,
             reservationDepositAmount, securityDepositAmount, remainingRentalAmount, dueAtCheckIn,
             totalInitialObligation, policyVersion, quotedAt, expiresAt);
    }

    public ReservationQuoteResponse(UUID quoteId, UUID facilityId, UUID unitTypeId,
                                    LocalDate startDate, LocalDate endDate, int rentalMonths,
                                    BigDecimal monthlyPrice, BigDecimal subtotal,
                                    String pricingPackageCode,
                                    BigDecimal discountRate, BigDecimal discountAmount,
                                    String voucherCode, BigDecimal voucherDiscountAmount,
                                    BigDecimal totalAfterDiscount, BigDecimal reservationDepositAmount,
                                    BigDecimal securityDepositAmount, BigDecimal remainingRentalAmount,
                                    BigDecimal dueAtCheckIn, BigDecimal totalInitialObligation,
                                    String policyVersion, Instant quotedAt, Instant expiresAt) {
        this(quoteId, facilityId, null, unitTypeId, null, startDate, endDate, rentalMonths, monthlyPrice, subtotal,
             pricingPackageCode, discountRate, discountAmount, voucherCode, voucherDiscountAmount, totalAfterDiscount,
             reservationDepositAmount, securityDepositAmount, remainingRentalAmount, dueAtCheckIn,
             totalInitialObligation, policyVersion, quotedAt, expiresAt);
    }

    public ReservationQuoteResponse(UUID quoteId, UUID facilityId, String facilityName,
                                    UUID unitTypeId, String unitTypeName,
                                    LocalDate startDate, LocalDate endDate, int rentalMonths,
                                    BigDecimal monthlyPrice, BigDecimal subtotal,
                                    String pricingPackageCode,
                                    BigDecimal discountRate, BigDecimal discountAmount,
                                    String voucherCode, BigDecimal voucherDiscountAmount,
                                    BigDecimal totalAfterDiscount, BigDecimal reservationDepositAmount,
                                    BigDecimal securityDepositAmount, BigDecimal remainingRentalAmount,
                                    BigDecimal dueAtCheckIn, BigDecimal totalInitialObligation,
                                    String policyVersion, Instant quotedAt, Instant expiresAt) {
        this.quoteId = quoteId;
        this.facilityId = facilityId;
        this.facilityName = facilityName;
        this.unitTypeId = unitTypeId;
        this.unitTypeName = unitTypeName;
        this.startDate = startDate;
        this.endDate = endDate;
        this.rentalMonths = rentalMonths;
        this.monthlyPrice = monthlyPrice;
        this.subtotal = subtotal;
        this.pricingPackageCode = pricingPackageCode;
        this.discountRate = discountRate;
        this.discountAmount = discountAmount;
        this.voucherCode = voucherCode;
        this.voucherDiscountAmount = voucherDiscountAmount != null ? voucherDiscountAmount : BigDecimal.ZERO;
        this.totalAfterDiscount = totalAfterDiscount;
        this.reservationDepositAmount = reservationDepositAmount;
        this.securityDepositAmount = securityDepositAmount;
        this.remainingRentalAmount = remainingRentalAmount;
        this.dueAtCheckIn = dueAtCheckIn;
        this.totalInitialObligation = totalInitialObligation;
        this.policyVersion = policyVersion;
        this.quotedAt = quotedAt;
        this.expiresAt = expiresAt;
    }

    public UUID getQuoteId() { return quoteId; }
    public UUID getFacilityId() { return facilityId; }
    public String getFacilityName() { return facilityName; }
    public void setFacilityName(String facilityName) { this.facilityName = facilityName; }
    public UUID getUnitTypeId() { return unitTypeId; }
    public String getUnitTypeName() { return unitTypeName; }
    public void setUnitTypeName(String unitTypeName) { this.unitTypeName = unitTypeName; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public int getRentalMonths() { return rentalMonths; }
    public int getFreeSetupDays() { return freeSetupDays; }
    public void setFreeSetupDays(int freeSetupDays) { this.freeSetupDays = freeSetupDays; }
    public BigDecimal getMonthlyPrice() { return monthlyPrice; }
    public BigDecimal getSubtotal() { return subtotal; }
    public String getPricingPackageCode() { return pricingPackageCode; }
    public BigDecimal getDiscountRate() { return discountRate; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public String getVoucherCode() { return voucherCode; }
    public BigDecimal getVoucherDiscountAmount() { return voucherDiscountAmount; }
    public BigDecimal getTotalAfterDiscount() { return totalAfterDiscount; }
    public BigDecimal getReservationDepositRate() { return reservationDepositRate != null ? reservationDepositRate : new BigDecimal("0.20"); }
    public void setReservationDepositRate(BigDecimal reservationDepositRate) { this.reservationDepositRate = reservationDepositRate; }
    public BigDecimal getReservationDepositAmount() { return reservationDepositAmount; }
    public int getEarlyTerminationFeeCapMonths() { return earlyTerminationFeeCapMonths; }
    public void setEarlyTerminationFeeCapMonths(int value) { this.earlyTerminationFeeCapMonths = value; }
    public BigDecimal getEarlyTerminationFeeCoefficient() { return earlyTerminationFeeCoefficient; }
    public void setEarlyTerminationFeeCoefficient(BigDecimal value) { this.earlyTerminationFeeCoefficient = value; }
    public int getEarlyTerminationNoticeDays() { return earlyTerminationNoticeDays; }
    public void setEarlyTerminationNoticeDays(int value) { this.earlyTerminationNoticeDays = value; }
    public BigDecimal getEarlyTerminationFee() { return earlyTerminationFee; }
    public void setEarlyTerminationFee(BigDecimal value) { this.earlyTerminationFee = value; }
    public BigDecimal getSecurityDepositAmount() { return securityDepositAmount; }
    public BigDecimal getRemainingRentalAmount() { return remainingRentalAmount; }
    public BigDecimal getDueAtCheckIn() { return dueAtCheckIn; }
    public BigDecimal getTotalInitialObligation() { return totalInitialObligation; }
    public String getPolicyVersion() { return policyVersion; }
    public Instant getQuotedAt() { return quotedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public List<EarlyTerminationTier> getEarlyTerminationTiers() { return earlyTerminationTiers; }
    public void setEarlyTerminationTiers(List<EarlyTerminationTier> tiers) {
        this.earlyTerminationTiers = tiers == null ? List.of() : List.copyOf(tiers);
    }
    public String getEarlyTerminationNoticeBasis() { return earlyTerminationNoticeBasis; }
    public void setEarlyTerminationNoticeBasis(String value) { this.earlyTerminationNoticeBasis = value; }
}
