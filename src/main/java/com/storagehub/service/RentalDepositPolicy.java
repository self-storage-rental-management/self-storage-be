package com.storagehub.service;
import java.math.BigDecimal;
/** Rental prepayment shared by Booking and new Renewal quotes; not a security deposit. */
public final class RentalDepositPolicy {
    public static final BigDecimal RATE = new BigDecimal("0.40");
    private RentalDepositPolicy() {}
}
