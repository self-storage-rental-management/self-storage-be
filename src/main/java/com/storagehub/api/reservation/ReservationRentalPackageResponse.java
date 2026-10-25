package com.storagehub.api.reservation;

import java.math.BigDecimal;

public record ReservationRentalPackageResponse(
    String code,
    String name,
    int rentalMonths,
    int freeSetupDays,
    BigDecimal discountRate,
    String policyVersion,
    BigDecimal depositRate
) {
    public ReservationRentalPackageResponse(
        String code,
        String name,
        int rentalMonths,
        BigDecimal discountRate,
        String policyVersion
    ) {
        this(code, name, rentalMonths, 0, discountRate, policyVersion, new BigDecimal("0.20"));
    }

    public ReservationRentalPackageResponse(
        String code,
        String name,
        int rentalMonths,
        BigDecimal discountRate,
        String policyVersion,
        BigDecimal depositRate
    ) {
        this(code, name, rentalMonths, 0, discountRate, policyVersion, depositRate);
    }

    public ReservationRentalPackageResponse(
        String code,
        String name,
        int rentalMonths,
        int freeSetupDays,
        BigDecimal discountRate,
        String policyVersion
    ) {
        this(code, name, rentalMonths, freeSetupDays, discountRate, policyVersion, new BigDecimal("0.20"));
    }
}
