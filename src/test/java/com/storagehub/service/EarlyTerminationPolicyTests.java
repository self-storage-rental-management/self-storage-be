package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class EarlyTerminationPolicyTests {

    @Test
    void feeIsCappedByTheReservationDepositAndOneMonthlyEquivalent() {
        assertThat(EarlyTerminationPolicy.fee(
            new BigDecimal("2280000"), new BigDecimal("11400000"), 12,
            BigDecimal.ONE, 1
        )).isEqualByComparingTo("950000");
    }

    @Test
    void setupWithoutGoodsChargesOnlyTheEarlyTerminationFee() {
        var result = EarlyTerminationPolicy.setupExit(
            new BigDecimal("11400000"), new BigDecimal("2280000"), new BigDecimal("1000000"),
            12, 15, 2, false, BigDecimal.ONE, 1
        );

        assertThat(result.type()).isEqualTo(EarlyTerminationPolicy.SetupExitType.NO_GOODS_MOVED_IN);
        assertThat(result.totalRentalCharge()).isEqualByComparingTo("950000");
        assertThat(result.rentalRefundAmount()).isEqualByComparingTo("10450000");
    }

    @Test
    void setupWithGoodsChargesUsedSetupDaysAtTheSnapshottedListPrice() {
        var result = EarlyTerminationPolicy.setupExit(
            new BigDecimal("11400000"), new BigDecimal("2280000"), new BigDecimal("1000000"),
            12, 15, 10, true, BigDecimal.ONE, 1
        );

        assertThat(result.earlyTerminationFee()).isEqualByComparingTo("950000");
        assertThat(result.usedGoodsCharge()).isEqualByComparingTo("333333");
        assertThat(result.totalRentalCharge()).isEqualByComparingTo("1283333");
        assertThat(result.rentalRefundAmount()).isEqualByComparingTo("10116667");
    }

    @Test
    void activeReturnBeforeThreeMonthsUsesListPriceAndEarlyTerminationFee() {
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
        assertThat(result.pricingTierMonths()).isZero();
        assertThat(result.earlyTerminationFee()).isEqualByComparingTo("950000");
        assertThat(result.totalRentalCharge()).isEqualByComparingTo("3450000");
        assertThat(result.rentalRefundAmount()).isEqualByComparingTo("7950000");
    }

    @Test
    void activeReturnUsesTheCompletedTierAndCapsFreeSetupDays() {
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
        assertThat(result.pricingTierMonths()).isEqualTo(6);
        assertThat(result.earlyTerminationFee()).isZero();
        assertThat(result.chargeableSetupDays()).isEqualTo(8);
        assertThat(result.noticeShortfallDays()).isEqualTo(0);
        assertThat(result.totalRentalCharge()).isLessThan(new BigDecimal("11400000"));
    }

    @Test
    void activeReturnAddsOnlyTheMissingNoticeDaysToTheSnapshotCharge() {
        var result = EarlyTerminationPolicy.activeReturn(
            LocalDate.of(2026, 1, 16), LocalDate.of(2026, 3, 16), LocalDate.of(2026, 1, 1),
            12, 15, new BigDecimal("1000000"), new BigDecimal("11400000"),
            new BigDecimal("2280000"), BigDecimal.ONE, 1, 14, 10, List.of(
                new EarlyTerminationPolicy.PricingTier(3, new BigDecimal("970000"), 3),
                new EarlyTerminationPolicy.PricingTier(12, new BigDecimal("950000"), 15)
            )
        );

        assertThat(result.noticeShortfallDays()).isEqualTo(4);
        assertThat(result.noticeCharge()).isEqualByComparingTo("133333");

        assertThat(EarlyTerminationPolicy.daysCharge(new BigDecimal("970000"), 8))
            .isEqualByComparingTo("258667");
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
