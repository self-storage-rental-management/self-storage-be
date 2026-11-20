package com.storagehub.api.reservation;

import java.math.BigDecimal;

public record ReservationPackagePricing(
    BigDecimal monthlyPrice,
    BigDecimal subtotal,
    BigDecimal discountRate,
    BigDecimal discountAmount,
    BigDecimal rentAfterPackageDiscount,
    BigDecimal securityDepositAmount,
    BigDecimal reservationDepositRate,
    BigDecimal reservationDepositAmount
) {}
