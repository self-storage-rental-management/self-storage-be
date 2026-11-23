package com.storagehub.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
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
        return setupExit(totalAfterDiscount, reservationDepositAmount, listMonthlyPrice, rentalMonths,
            freeSetupDays, usedSetupDays, goodsMovedIn, feeCoefficient, feeCapMonths, false);
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
        int feeCapMonths,
        boolean waiveEarlyTerminationFee
    ) {
        BigDecimal total = money(totalAfterDiscount);
        BigDecimal fee = waiveEarlyTerminationFee
            ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            : fee(reservationDepositAmount, total, rentalMonths, feeCoefficient, feeCapMonths);
        int billableDays = goodsMovedIn ? Math.max(0, Math.min(usedSetupDays, freeSetupDays)) : 0;
        BigDecimal usedGoodsCharge = goodsMovedIn
            ? daysCharge(listMonthlyPrice, billableDays)
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalCharge = fee.add(usedGoodsCharge).min(total).setScale(2, RoundingMode.HALF_UP);
        return new SetupExitCalculation(
            goodsMovedIn ? SetupExitType.GOODS_MOVED_IN : SetupExitType.NO_GOODS_MOVED_IN,
            fee,
            billableDays,
            usedGoodsCharge,
            totalCharge,
            total.subtract(totalCharge).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
        );
    }

    /**
     * Inventory mismatch exits are not charged the fixed early-termination fee.
     * They settle only the verified usage time and return the unused prepaid rent.
     */
    public static SetupExitCalculation inventoryMismatchExit(
        BigDecimal totalAfterDiscount,
        BigDecimal listMonthlyPrice,
        int usedDays,
        boolean goodsMovedIn
    ) {
        BigDecimal total = money(totalAfterDiscount);
        // Receipt grants use of the unit, regardless of the move-in declaration.
        BigDecimal usageCharge = daysCharge(listMonthlyPrice, Math.max(1, usedDays));
        return new SetupExitCalculation(
            SetupExitType.GOODS_MOVED_IN,
            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
            Math.max(1, usedDays),
            usageCharge,
            usageCharge,
            total.subtract(usageCharge).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
        );
    }

    /**
     * Inventory-mismatch returns use calendar dates agreed in the return request.
     * Both the receipt date and the scheduled return date count as used days;
     * the same calendar date therefore counts as one day.
     */
    public static int calendarUsageDays(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) return 0;
        return Math.max(1, Math.toIntExact(ChronoUnit.DAYS.between(startDate, endDate) + 1));
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

        int completedMonths = (int) Math.max(0,
            ChronoUnit.MONTHS.between(paidRentalStart, returnDate.plusDays(1)));
        int setupDaysUsed = setupStart == null
            ? Math.max(0, originalFreeSetupDays)
            : (int) Math.max(0, ChronoUnit.DAYS.between(setupStart, paidRentalStart));
        int rentalDaysUsed = (int) Math.max(0,
            ChronoUnit.DAYS.between(paidRentalStart, returnDate.plusDays(1)));
        int requestedNoticeShortfallDays = (int) Math.max(0, noticeDays - Math.max(0, noticeGivenDays));
        int remainingTermDays = (int) Math.max(0, ChronoUnit.DAYS.between(returnDate, fullTermEnd));
        // Notice compensation cannot extend beyond the remaining contract term.
        int noticeShortfallDays = Math.min(requestedNoticeShortfallDays, remainingTermDays);

        PricingTier tier = pricingTiers == null ? null : pricingTiers.stream()
            .filter(value -> value != null && value.rentalMonths() >= 3
                && value.rentalMonths() <= completedMonths)
            .max(Comparator.comparingInt(PricingTier::rentalMonths))
            .orElse(null);
        boolean underThreeMonths = completedMonths < 3;
        BigDecimal monthlyPrice = underThreeMonths
            ? money(listMonthlyPrice)
            : tier == null ? money(listMonthlyPrice) : money(tier.monthlyPrice());
        int freeSetupDays = underThreeMonths
            ? 0
            : tier == null ? 0 : Math.max(0, tier.freeSetupDays());
        int chargeableSetupDays = Math.max(0, setupDaysUsed - freeSetupDays);
        int chargeableRentalDays = rentalDaysUsed + noticeShortfallDays;
        BigDecimal dailyPrice = monthlyPrice.divide(BigDecimal.valueOf(DAYS_PER_MONTH), 0, RoundingMode.HALF_UP);
        BigDecimal usageCharge = daysCharge(monthlyPrice, chargeableSetupDays + chargeableRentalDays);
        BigDecimal earlyTerminationFee = underThreeMonths
            ? fee(reservationDepositAmount, total, originalRentalMonths, feeCoefficient, feeCapMonths)
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal noticeCharge = daysCharge(monthlyPrice, noticeShortfallDays);
        BigDecimal totalCharge = usageCharge.add(earlyTerminationFee)
            .min(total)
            .setScale(2, RoundingMode.HALF_UP);
        Integer pricingTierMonths = underThreeMonths
            ? Integer.valueOf(0)
            : tier == null ? null : Integer.valueOf(tier.rentalMonths());
        return new ActiveReturnCalculation(
            true,
            completedMonths,
            pricingTierMonths,
            setupDaysUsed,
            chargeableSetupDays,
            rentalDaysUsed,
            noticeShortfallDays,
            dailyPrice,
            earlyTerminationFee,
            noticeCharge,
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
