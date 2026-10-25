package com.storagehub.service.renewal;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RenewalRentCalculationTests {
    private static BigDecimal n(String value) { return new BigDecimal(value); }

    @Test void prepaymentIsIncludedInRenewalRentWithoutAnotherSecurityDeposit() {
        var terms = RenewalTermCalculator.calculate(LocalDate.of(2026, 10, 25), 1,
            n("5000000"), BigDecimal.ZERO, n("0.20"));
        assertThat(terms.netRent()).isEqualByComparingTo("5000000");
        assertThat(terms.depositAmount()).isEqualByComparingTo("1000000");
        assertThat(terms.remainder()).isEqualByComparingTo("4000000");
        assertThat(terms.depositAmount().add(terms.remainder())).isEqualByComparingTo(terms.netRent());
    }

    @Test void discountAppliesOnceBeforeSplittingPrepaymentAndBalance() {
        var terms = RenewalTermCalculator.calculate(LocalDate.of(2026, 10, 25), 3,
            n("1000000"), n("0.10"), n("0.20"));
        assertThat(terms.subtotal()).isEqualByComparingTo("3000000");
        assertThat(terms.discountAmount()).isEqualByComparingTo("300000");
        assertThat(terms.netRent()).isEqualByComparingTo("2700000");
        assertThat(terms.depositAmount()).isEqualByComparingTo("540000");
        assertThat(terms.remainder()).isEqualByComparingTo("2160000");
    }

    @Test void sharedCalculationPreservesRoundingAndRejectsInvalidDiscounts() {
        var rent = RenewalRentCalculation.calculate(n("100.01"), 3, n("0.075"));
        assertThat(rent.subtotal()).isEqualByComparingTo("300.03");
        assertThat(rent.discount()).isEqualByComparingTo("22.50");
        assertThat(rent.netRent()).isEqualByComparingTo("277.53");
        assertThatThrownBy(() -> RenewalRentCalculation.calculate(n("100"), 1, n("1.1")))
            .hasMessageContaining("invalid");
    }
}
