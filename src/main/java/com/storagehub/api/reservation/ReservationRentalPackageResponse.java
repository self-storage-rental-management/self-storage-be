package com.storagehub.api.reservation;

import java.math.BigDecimal;

public record ReservationRentalPackageResponse(
    String code,
    String name,
    int rentalMonths,
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
        this(code, name, rentalMonths, discountRate, policyVersion, new BigDecimal("0.20"));
    }
}
