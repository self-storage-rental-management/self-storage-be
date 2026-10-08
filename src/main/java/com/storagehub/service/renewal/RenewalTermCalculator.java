package com.storagehub.service.renewal;

import com.storagehub.common.api.ApiExceptions;
import java.math.*;
import java.time.*;

/** Deterministic calculation only; a caller must first obtain and validate real shared policy/price data. */
public final class RenewalTermCalculator {
    private RenewalTermCalculator() {}
    public record Terms(LocalDate startDate, LocalDate endExclusive, LocalDate newEndDate,
        BigDecimal subtotal, BigDecimal discountAmount, BigDecimal netRent,
        BigDecimal depositAmount, BigDecimal remainder) {}
    public static Terms calculate(LocalDate oldEnd, int packageMonths, BigDecimal price,
        BigDecimal discountRate, BigDecimal depositRate) {
        if (oldEnd == null || packageMonths < 1 || price == null || price.signum() < 0
            || !rate(discountRate) || !rate(depositRate))
            throw ApiExceptions.conflict("Shared renewal pricing or period data is invalid");
        try {
            LocalDate start = oldEnd.plusDays(1), end = start.plusMonths(packageMonths);
            BigDecimal subtotal = money(price.multiply(BigDecimal.valueOf(packageMonths)));
            BigDecimal discount = money(subtotal.multiply(discountRate));
            BigDecimal net = money(subtotal.subtract(discount));
            BigDecimal deposit = money(net.multiply(depositRate));
            return new Terms(start,end,end.minusDays(1),subtotal,discount,net,deposit,money(net.subtract(deposit)));
        } catch (DateTimeException | ArithmeticException e) {
            throw ApiExceptions.conflict("Shared renewal period is outside supported date or money bounds");
        }
    }
    private static boolean rate(BigDecimal r) { return r != null && r.signum() >= 0 && r.compareTo(BigDecimal.ONE) <= 0; }
    private static BigDecimal money(BigDecimal v) { return v.setScale(2,RoundingMode.HALF_UP); }
}
