package com.storagehub.api.reservation;

import java.math.BigDecimal;

public record ReservationRentalPackageResponse(
    String code,
    String name,
    int rentalMonths,
    BigDecimal discountRate,
    String policyVersion
) {
}
