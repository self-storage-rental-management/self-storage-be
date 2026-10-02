package com.storagehub.api.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class ReservationQuoteResponse {

    private UUID quoteId;
    private UUID facilityId;
    private UUID unitTypeId;
    private LocalDate startDate;
    private LocalDate endDate;
    private int rentalMonths;
    private BigDecimal monthlyPrice;
    private BigDecimal subtotal;
    private BigDecimal discountRate;
    private BigDecimal discountAmount;
    private BigDecimal totalAfterDiscount;
    private BigDecimal reservationDepositAmount;
    private BigDecimal securityDepositAmount;
    private BigDecimal remainingRentalAmount;
    private BigDecimal dueAtCheckIn;
    private BigDecimal totalInitialObligation;
    private String policyVersion;
    private Instant quotedAt;
    private Instant expiresAt;

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
        this.quoteId = quoteId;
        this.facilityId = facilityId;
        this.unitTypeId = unitTypeId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.rentalMonths = rentalMonths;
        this.monthlyPrice = monthlyPrice;
        this.subtotal = subtotal;
        this.discountRate = discountRate;
        this.discountAmount = discountAmount;
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
    public UUID getUnitTypeId() { return unitTypeId; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public int getRentalMonths() { return rentalMonths; }
    public BigDecimal getMonthlyPrice() { return monthlyPrice; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getDiscountRate() { return discountRate; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getTotalAfterDiscount() { return totalAfterDiscount; }
    public BigDecimal getReservationDepositAmount() { return reservationDepositAmount; }
    public BigDecimal getSecurityDepositAmount() { return securityDepositAmount; }
    public BigDecimal getRemainingRentalAmount() { return remainingRentalAmount; }
    public BigDecimal getDueAtCheckIn() { return dueAtCheckIn; }
    public BigDecimal getTotalInitialObligation() { return totalInitialObligation; }
    public String getPolicyVersion() { return policyVersion; }
    public Instant getQuotedAt() { return quotedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
