package com.storagehub.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Immutable pricing rules captured by a reservation quote. This class only
 * calculates the commercial recommendation; Business still approves the
 * actual return/refund settlement and the security deposit remains separate.
 */
public final class EarlyTerminationPolicy {
    public static final String NOTICE_BASIS = "REQUESTED_AT_TO_SCHEDULED_DATE";

    public static final int DEFAULT_FEE_CAP_MONTHS = 1;
    public static final BigDecimal DEFAULT_FEE_COEFFICIENT = BigDecimal.ONE;
    public static final int DEFAULT_NOTICE_DAYS = 14;
    private static final int DAYS_PER_MONTH = 30;

    private EarlyTerminationPolicy() {
    }

    public static BigDecimal fee(
        BigDecimal reservationDepositAmount,
        BigDecimal totalAfterDiscount,
        int rentalMonths,
        BigDecimal feeCoefficient,
        int feeCapMonths
    ) {
        if (reservationDepositAmount == null || totalAfterDiscount == null || rentalMonths <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal monthlyEquivalent = totalAfterDiscount
            .divide(BigDecimal.valueOf(rentalMonths), 2, RoundingMode.HALF_UP);
        BigDecimal cap = monthlyEquivalent.multiply(BigDecimal.valueOf(Math.max(1, feeCapMonths)));
        BigDecimal coefficient = feeCoefficient == null || feeCoefficient.signum() < 0
            ? DEFAULT_FEE_COEFFICIENT : feeCoefficient;
        return reservationDepositAmount.min(cap.multiply(coefficient))
            .max(BigDecimal.ZERO)
            .setScale(2, RoundingMode.HALF_UP);
    }

    public static SetupExitCalculation setupExit(
        BigDecimal totalAfterDiscount,
        BigDecimal reservationDepositAmount,
        BigDecimal listMonthlyPrice,
        int rentalMonths,
        int freeSetupDays,
        int usedSetupDays,
        boolean goodsMovedIn,
        BigDecimal feeCoefficient,
        int feeCapMonths
    ) {
        BigDecimal total = money(totalAfterDiscount);
        // A return during setup keeps 20% of the rental amount and refunds 80%.
        // The security deposit is settled separately after inspection.
        BigDecimal totalCharge = SetupEarlyExitPolicy.retainedRental(total)
            .min(total)
            .setScale(2, RoundingMode.HALF_UP);
        return new SetupExitCalculation(
            goodsMovedIn ? SetupExitType.GOODS_MOVED_IN : SetupExitType.NO_GOODS_MOVED_IN,
            totalCharge,
            0,
            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
            totalCharge,
            total.subtract(totalCharge).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
        );
    }

    /**
     * Reprices an early return after ACTIVE using only the pricing snapshot.
     * The security deposit is deliberately excluded from this calculation.
     */
    public static ActiveReturnCalculation activeReturn(
        LocalDate paidRentalStart,
        LocalDate returnDate,
        LocalDate setupStart,
        int originalRentalMonths,
        int originalFreeSetupDays,
        BigDecimal listMonthlyPrice,
        BigDecimal totalAfterDiscount,
        BigDecimal reservationDepositAmount,
        BigDecimal feeCoefficient,
        int feeCapMonths,
        int noticeDays,
        long noticeGivenDays,
        List<PricingTier> pricingTiers
    ) {
        BigDecimal total = money(totalAfterDiscount);
        if (paidRentalStart == null || returnDate == null || originalRentalMonths <= 0
            || returnDate.isBefore(paidRentalStart)) {
            return ActiveReturnCalculation.notRequired();
        }

        LocalDate fullTermEnd = paidRentalStart.plusMonths(originalRentalMonths).minusDays(1);
        if (!returnDate.isBefore(fullTermEnd)) {
            return ActiveReturnCalculation.notRequired();
        }

        int completedMonths = (int) Math.max(0, ChronoUnit.MONTHS.between(paidRentalStart, returnDate));
        int rentalDaysUsed = (int) Math.max(0,
            ChronoUnit.DAYS.between(paidRentalStart, returnDate.plusDays(1)));

        // A started calendar month is charged in full. This means a return on
        // the contract start day charges month 1 and refunds all later months;
        // returning on day 10 of a 3-month term still refunds months 2 and 3.
        int chargedMonths = Math.min(originalRentalMonths, Math.max(1, completedMonths + 1));
        BigDecimal monthlyEquivalent = total.divide(BigDecimal.valueOf(originalRentalMonths), 0, RoundingMode.HALF_UP);
        BigDecimal dailyPrice = monthlyEquivalent.divide(BigDecimal.valueOf(DAYS_PER_MONTH), 0, RoundingMode.HALF_UP);
        BigDecimal totalCharge = monthlyEquivalent.multiply(BigDecimal.valueOf(chargedMonths))
            .min(total)
            .setScale(2, RoundingMode.HALF_UP);
        return new ActiveReturnCalculation(
            true,
            completedMonths,
            null,
            0,
            0,
            rentalDaysUsed,
            0,
            dailyPrice,
            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
            totalCharge,
            total.subtract(totalCharge).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
        );
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            : value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * R6: keep all fractional-day value together and round once at the end.
     * VND has no fractional unit, so this deliberately returns an integer amount.
     */
    public static BigDecimal daysCharge(BigDecimal monthlyPrice, long days) {
        if (monthlyPrice == null || days <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return monthlyPrice.multiply(BigDecimal.valueOf(days))
            .divide(BigDecimal.valueOf(DAYS_PER_MONTH), 0, RoundingMode.HALF_UP)
            .setScale(2, RoundingMode.HALF_UP);
    }

    public enum SetupExitType {
        NO_GOODS_MOVED_IN,
        GOODS_MOVED_IN
    }

    public record SetupExitCalculation(
        SetupExitType type,
        BigDecimal earlyTerminationFee,
        int billableSetupDays,
        BigDecimal usedGoodsCharge,
        BigDecimal totalRentalCharge,
        BigDecimal rentalRefundAmount
    ) {
    }

    public record PricingTier(int rentalMonths, BigDecimal monthlyPrice, int freeSetupDays) {
    }

    public record ActiveReturnCalculation(
        boolean recalculationRequired,
        int completedRentalMonths,
        Integer pricingTierMonths,
        int setupDaysUsed,
        int chargeableSetupDays,
        int rentalDaysUsed,
        int noticeShortfallDays,
        BigDecimal dailyPrice,
        BigDecimal earlyTerminationFee,
        BigDecimal noticeCharge,
        BigDecimal totalRentalCharge,
        BigDecimal rentalRefundAmount
    ) {
        private static ActiveReturnCalculation notRequired() {
            return new ActiveReturnCalculation(
                false, 0, null, 0, 0, 0, 0,
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            );
        }
    }
}
