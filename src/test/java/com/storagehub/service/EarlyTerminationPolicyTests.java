package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class EarlyTerminationPolicyTests {

    @Test
    void receiptUsageDoesNotDependOnMoveInFlagOrPrepaidCap() {
        var result = EarlyTerminationPolicy.inventoryMismatchExit(
            new BigDecimal("100000"), new BigDecimal("2000000"), 3, false);
        assertThat(result.billableSetupDays()).isEqualTo(3);
        assertThat(result.totalRentalCharge()).isEqualByComparingTo("200000");
        assertThat(result.earlyTerminationFee()).isEqualByComparingTo("0");
        assertThat(result.rentalRefundAmount()).isEqualByComparingTo("0");
    }

    @Test
    void feeIsCappedByTheReservationDepositAndOneMonthlyEquivalent() {
        assertThat(EarlyTerminationPolicy.fee(
            new BigDecimal("2280000"), new BigDecimal("11400000"), 12,
            BigDecimal.ONE, 1
        )).isEqualByComparingTo("950000");
    }

    @Test
    void setupReturnWithoutGoodsRetainsTwentyPercentOfTheAcceptedRental() {
        // V2: a return during setup always retains 20% of the accepted rental and refunds 80%.
        // It no longer adds a one-month early-termination fee or a used-goods charge.
        var result = EarlyTerminationPolicy.setupExit(
            new BigDecimal("11400000"), new BigDecimal("2280000"), new BigDecimal("1000000"),
            12, 15, 2, false, BigDecimal.ONE, 1
        );

        assertThat(result.type()).isEqualTo(EarlyTerminationPolicy.SetupExitType.NO_GOODS_MOVED_IN);
        assertThat(result.earlyTerminationFee()).isEqualByComparingTo("2280000");
        assertThat(result.billableSetupDays()).isZero();
        assertThat(result.usedGoodsCharge()).isEqualByComparingTo("0");
        assertThat(result.totalRentalCharge()).isEqualByComparingTo("2280000");
        assertThat(result.rentalRefundAmount()).isEqualByComparingTo("9120000");
    }

    @Test
    void setupReturnWithGoodsMovedInStillRetainsOnlyTwentyPercentOfTheRental() {
        var result = EarlyTerminationPolicy.setupExit(
            new BigDecimal("11400000"), new BigDecimal("2280000"), new BigDecimal("1000000"),
            12, 15, 10, true, BigDecimal.ONE, 1
        );

        assertThat(result.type()).isEqualTo(EarlyTerminationPolicy.SetupExitType.GOODS_MOVED_IN);
        assertThat(result.earlyTerminationFee()).isEqualByComparingTo("2280000");
        assertThat(result.billableSetupDays()).isZero();
        assertThat(result.usedGoodsCharge()).isEqualByComparingTo("0");
        assertThat(result.totalRentalCharge()).isEqualByComparingTo("2280000");
        assertThat(result.rentalRefundAmount()).isEqualByComparingTo("9120000");
    }

    @Test
    void inventoryMismatchFeeWaiverFlagDoesNotChangeTheSetupPolicy() {
        // `waiveEarlyTerminationFee` no longer matters for setupExit: inventory-mismatch
        // fee waiving is applied by inventoryMismatchExit(...), not through this flag.
        var result = EarlyTerminationPolicy.setupExit(
            new BigDecimal("11400000"), new BigDecimal("2280000"), new BigDecimal("1000000"),
            12, 15, 10, true, BigDecimal.ONE, 1, true
        );

        assertThat(result.earlyTerminationFee()).isEqualByComparingTo("2280000");
        assertThat(result.usedGoodsCharge()).isEqualByComparingTo("0");
        assertThat(result.totalRentalCharge()).isEqualByComparingTo("2280000");
        assertThat(result.rentalRefundAmount()).isEqualByComparingTo("9120000");
    }

    @Test
    void inventoryMismatchReturnWaivesEarlyTerminationFeeButKeepsUsedSetupCharge() {
        var result = EarlyTerminationPolicy.inventoryMismatchExit(
            new BigDecimal("11400000"), new BigDecimal("1000000"), 10, true
        );

        assertThat(result.earlyTerminationFee()).isZero();
        assertThat(result.usedGoodsCharge()).isEqualByComparingTo("333333");
        assertThat(result.totalRentalCharge()).isEqualByComparingTo("333333");
    }

    @Test
    void inventoryMismatchReturnUsesCalendarDaysWithoutFixedFee() {
        var oneDay = EarlyTerminationPolicy.inventoryMismatchExit(
            new BigDecimal("12000000"), new BigDecimal("3000000"), 1, true
        );
        var twoDays = EarlyTerminationPolicy.inventoryMismatchExit(
            new BigDecimal("12000000"), new BigDecimal("3000000"), 2, true
        );

        assertThat(oneDay.earlyTerminationFee()).isZero();
        assertThat(oneDay.usedGoodsCharge()).isEqualByComparingTo("100000");
        assertThat(twoDays.usedGoodsCharge()).isEqualByComparingTo("200000");
        assertThat(twoDays.rentalRefundAmount()).isEqualByComparingTo("11800000");
    }

    @Test
    void inventoryMismatchCalendarUsageIncludesBothReceiptAndScheduledReturnDates() {
        assertThat(EarlyTerminationPolicy.calendarUsageDays(
            LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12)
        )).isEqualTo(1);
        assertThat(EarlyTerminationPolicy.calendarUsageDays(
            LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14)
        )).isEqualTo(3);
    }

    @Test
    void activeReturnBeforeThreeMonthsChargesEveryStartedMonthWithoutAnEarlyTerminationFee() {
        // V2 repricing: total 11,400,000 / 12 months = 950,000 per month. A return on
        // 2026-03-16 has completed 2 months, so month 3 is charged in full (2,850,000).
        var result = EarlyTerminationPolicy.activeReturn(
            LocalDate.of(2026, 1, 16), LocalDate.of(2026, 3, 16), LocalDate.of(2026, 1, 1),
            12, 15, new BigDecimal("1000000"), new BigDecimal("11400000"),
            new BigDecimal("2280000"), BigDecimal.ONE, 1, 14, 14, List.of(
                new EarlyTerminationPolicy.PricingTier(3, new BigDecimal("970000"), 3),
                new EarlyTerminationPolicy.PricingTier(6, new BigDecimal("970000"), 7),
                new EarlyTerminationPolicy.PricingTier(12, new BigDecimal("950000"), 15)
            )
        );

        assertThat(result.recalculationRequired()).isTrue();
        assertThat(result.completedRentalMonths()).isEqualTo(2);
        assertThat(result.rentalDaysUsed()).isEqualTo(60);
        assertThat(result.pricingTierMonths()).isNull();
        assertThat(result.chargeableSetupDays()).isZero();
        assertThat(result.dailyPrice()).isEqualByComparingTo("31667");
        assertThat(result.earlyTerminationFee()).isZero();
        assertThat(result.totalRentalCharge()).isEqualByComparingTo("2850000");
        assertThat(result.rentalRefundAmount()).isEqualByComparingTo("8550000");
    }

    @Test
    void activeReturnChargesTheStartedMonthsAtTheRentalEquivalent() {
        var result = EarlyTerminationPolicy.activeReturn(
            LocalDate.of(2026, 1, 16), LocalDate.of(2026, 8, 16), LocalDate.of(2026, 1, 1),
            12, 15, new BigDecimal("1000000"), new BigDecimal("11400000"),
            new BigDecimal("2280000"), BigDecimal.ONE, 1, 14, 20, List.of(
                new EarlyTerminationPolicy.PricingTier(3, new BigDecimal("980000"), 3),
                new EarlyTerminationPolicy.PricingTier(6, new BigDecimal("970000"), 7),
                new EarlyTerminationPolicy.PricingTier(12, new BigDecimal("950000"), 15)
            )
        );

        // 7 completed months charge month 8 at 950,000 per month = 7,600,000.
        assertThat(result.completedRentalMonths()).isEqualTo(7);
        assertThat(result.pricingTierMonths()).isNull();
        assertThat(result.earlyTerminationFee()).isZero();
        assertThat(result.chargeableSetupDays()).isZero();
        assertThat(result.noticeShortfallDays()).isEqualTo(0);
        assertThat(result.totalRentalCharge()).isEqualByComparingTo("7600000");
        assertThat(result.rentalRefundAmount()).isEqualByComparingTo("3800000");
    }

    @Test
    void activeReturnNoLongerAddsANoticeShortfallCharge() {
        // V2 dropped the notice-shortfall charge; the days of notice given no longer
        // change the settlement, only the started calendar months do.
        var withFullNotice = EarlyTerminationPolicy.activeReturn(
            LocalDate.of(2026, 1, 16), LocalDate.of(2026, 3, 16), LocalDate.of(2026, 1, 1),
            12, 15, new BigDecimal("1000000"), new BigDecimal("11400000"),
            new BigDecimal("2280000"), BigDecimal.ONE, 1, 14, 14, List.of()
        );
        var withShortNotice = EarlyTerminationPolicy.activeReturn(
            LocalDate.of(2026, 1, 16), LocalDate.of(2026, 3, 16), LocalDate.of(2026, 1, 1),
            12, 15, new BigDecimal("1000000"), new BigDecimal("11400000"),
            new BigDecimal("2280000"), BigDecimal.ONE, 1, 14, 10, List.of()
        );

        assertThat(withShortNotice.noticeShortfallDays()).isZero();
        assertThat(withShortNotice.noticeCharge()).isZero();
        assertThat(withShortNotice.totalRentalCharge())
            .isEqualByComparingTo(withFullNotice.totalRentalCharge());
        assertThat(EarlyTerminationPolicy.daysCharge(new BigDecimal("970000"), 8))
            .isEqualByComparingTo("258667");
    }

    @Test
    void activeReturnChargesTheLastStartedMonthWithoutANoticeShortfall() {
        // Returning 2027-01-12 leaves the 12th month of a 12-month term already started,
        // so the whole prepaid rent is charged and nothing remains to refund.
        var result = EarlyTerminationPolicy.activeReturn(
            LocalDate.of(2026, 1, 16), LocalDate.of(2027, 1, 12), LocalDate.of(2026, 1, 16),
            12, 15, new BigDecimal("1000000"), new BigDecimal("12000000"),
            new BigDecimal("2400000"), BigDecimal.ONE, 1, 14, 0, List.of()
        );

        assertThat(result.completedRentalMonths()).isEqualTo(11);
        assertThat(result.noticeShortfallDays()).isZero();
        assertThat(result.noticeCharge()).isZero();
        assertThat(result.totalRentalCharge()).isEqualByComparingTo("12000000");
        assertThat(result.rentalRefundAmount()).isEqualByComparingTo("0");
    }

    @Test
    void fullTermReturnDoesNotRepriceOrChargeEarlyTerminationFee() {
        var result = EarlyTerminationPolicy.activeReturn(
            LocalDate.of(2026, 1, 16), LocalDate.of(2050, 1, 15), LocalDate.of(2026, 1, 1),
            24, 30, new BigDecimal("1000000"), new BigDecimal("11400000"),
            new BigDecimal("2280000"), BigDecimal.ONE, 1, 14, 14, List.of()
        );

        assertThat(result.recalculationRequired()).isFalse();
        assertThat(result.totalRentalCharge()).isZero();
        assertThat(result.rentalRefundAmount()).isZero();
    }
}
