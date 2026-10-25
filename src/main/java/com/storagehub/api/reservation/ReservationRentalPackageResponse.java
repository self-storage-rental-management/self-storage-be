package com.storagehub.api.reservation;

import java.math.BigDecimal;

public record ReservationRentalPackageResponse(
    String code,
    String name,
    int rentalMonths,
    int freeSetupDays,
    BigDecimal discountRate,
    String policyVersion
) {
    public ReservationRentalPackageResponse(
        String code, String name, int rentalMonths, BigDecimal discountRate, String policyVersion
    ) {
        this(code, name, rentalMonths, 0, discountRate, policyVersion);
    }
}
