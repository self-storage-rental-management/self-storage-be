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
    void setupWithoutGoodsRetainsTwentyPercent() {
        var result = EarlyTerminationPolicy.setupExit(
            new BigDecimal("11400000"), new BigDecimal("2280000"), new BigDecimal("1000000"),
            12, 15, 2, false, BigDecimal.ONE, 1
        );

        assertThat(result.type()).isEqualTo(EarlyTerminationPolicy.SetupExitType.NO_GOODS_MOVED_IN);
        assertThat(result.totalRentalCharge()).isEqualByComparingTo("2280000");
        assertThat(result.rentalRefundAmount()).isEqualByComparingTo("9120000");
    }

    @Test
    void setupWithGoodsRetainsTheSameTwentyPercent() {
        var result = EarlyTerminationPolicy.setupExit(
            new BigDecimal("11400000"), new BigDecimal("2280000"), new BigDecimal("1000000"),
            12, 15, 10, true, BigDecimal.ONE, 1
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
    void activeReturnChargesEachStartedMonthAtContractPrice() {
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
        assertThat(result.pricingTierMonths()).isNull();
        assertThat(result.earlyTerminationFee()).isZero();
        assertThat(result.totalRentalCharge()).isEqualByComparingTo("2850000");
        assertThat(result.rentalRefundAmount()).isEqualByComparingTo("8550000");
    }

    @Test
    void activeReturnIgnoresHistoricPricingTiersAndSetupDays() {
        var result = EarlyTerminationPolicy.activeReturn(
            LocalDate.of(2026, 1, 16), LocalDate.of(2026, 8, 16), LocalDate.of(2026, 1, 1),
            12, 15, new BigDecimal("1000000"), new BigDecimal("11400000"),
            new BigDecimal("2280000"), BigDecimal.ONE, 1, 14, 20, List.of(
                new EarlyTerminationPolicy.PricingTier(3, new BigDecimal("980000"), 3),
                new EarlyTerminationPolicy.PricingTier(6, new BigDecimal("970000"), 7),
                new EarlyTerminationPolicy.PricingTier(12, new BigDecimal("950000"), 15)
            )
        );

        assertThat(result.completedRentalMonths()).isEqualTo(7);
        assertThat(result.pricingTierMonths()).isNull();
        assertThat(result.earlyTerminationFee()).isZero();
        assertThat(result.chargeableSetupDays()).isZero();
        assertThat(result.noticeShortfallDays()).isEqualTo(0);
        assertThat(result.totalRentalCharge()).isLessThan(new BigDecimal("11400000"));
    }

    @Test
    void shortNoticeAddsNoChargeToStartedMonthBilling() {
        var result = EarlyTerminationPolicy.activeReturn(
            LocalDate.of(2026, 1, 16), LocalDate.of(2026, 3, 16), LocalDate.of(2026, 1, 1),
            12, 15, new BigDecimal("1000000"), new BigDecimal("11400000"),
            new BigDecimal("2280000"), BigDecimal.ONE, 1, 14, 10, List.of(
                new EarlyTerminationPolicy.PricingTier(3, new BigDecimal("970000"), 3),
                new EarlyTerminationPolicy.PricingTier(12, new BigDecimal("950000"), 15)
            )
        );

        assertThat(result.noticeShortfallDays()).isZero();
        assertThat(result.noticeCharge()).isZero();

        assertThat(EarlyTerminationPolicy.daysCharge(new BigDecimal("970000"), 8))
            .isEqualByComparingTo("258667");
    }

    @Test
    void lastMonthReturnChargesTheFullTermWithoutNoticeFee() {
        var result = EarlyTerminationPolicy.activeReturn(
            LocalDate.of(2026, 1, 16), LocalDate.of(2027, 1, 12), LocalDate.of(2026, 1, 16),
            12, 15, new BigDecimal("1000000"), new BigDecimal("12000000"),
            new BigDecimal("2400000"), BigDecimal.ONE, 1, 14, 0, List.of()
        );

        assertThat(result.noticeShortfallDays()).isZero();
        assertThat(result.noticeCharge()).isZero();
        assertThat(result.totalRentalCharge()).isEqualByComparingTo("12000000");
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
