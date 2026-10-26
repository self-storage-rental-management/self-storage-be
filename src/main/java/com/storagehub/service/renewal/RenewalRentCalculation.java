package com.storagehub.service.renewal;

import com.storagehub.common.api.ApiExceptions;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** One source for renewal rent: prepayment is part of net rent, never a security deposit. */
public final class RenewalRentCalculation {
    private RenewalRentCalculation() {}
    public record Amounts(BigDecimal subtotal, BigDecimal discount, BigDecimal netRent) {}

    public static Amounts calculate(BigDecimal monthlyPrice, int months, BigDecimal discountRate) {
        if (monthlyPrice == null || monthlyPrice.signum() < 0 || months < 1 || discountRate == null
            || discountRate.signum() < 0 || discountRate.compareTo(BigDecimal.ONE) > 0)
            throw ApiExceptions.conflict("Shared renewal pricing or period data is invalid");
        BigDecimal subtotal = money(monthlyPrice.multiply(BigDecimal.valueOf(months)));
        BigDecimal discount = money(subtotal.multiply(discountRate));
        return new Amounts(subtotal, discount, money(subtotal.subtract(discount)));
    }
    private static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
}
