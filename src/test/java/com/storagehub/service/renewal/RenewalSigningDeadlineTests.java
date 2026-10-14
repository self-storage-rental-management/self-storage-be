package com.storagehub.service.renewal;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class RenewalSigningDeadlineTests {
    @Test void threeDayWindowDoesNotEndAtTheThirtyMinuteAppointment() {
        Instant paid=Instant.parse("2026-10-13T06:50:14Z");
        assertThat(RenewalSigningDeadline.calculate(paid,Instant.parse("2026-11-01T10:00:00Z")))
            .isEqualTo(Instant.parse("2026-10-16T06:50:14Z"));
    }
    @Test void lateDepositCannotExtendRecoveryDeadline() {
        assertThat(RenewalSigningDeadline.calculate(Instant.parse("2026-10-16T06:00:00Z"),Instant.parse("2026-10-17T10:00:00Z")))
            .isEqualTo(Instant.parse("2026-10-17T10:00:00Z"));
    }
    @Test void signingWindowPreservesTimeAcrossMidnightAndDatabasePrecision() {
        assertThat(RenewalSigningDeadline.calculate(Instant.parse("2026-10-13T16:59:59.123456789Z"),Instant.parse("2026-11-01T10:00:00Z")))
            .isEqualTo(Instant.parse("2026-10-16T16:59:59.123456Z"));
    }
    @Test void missingPaidAtOrRecoveryCutoffIsNotAssumedReady() {
        assertThatThrownBy(()->RenewalSigningDeadline.calculate(null,Instant.now())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(()->RenewalSigningDeadline.calculate(Instant.now(),null)).isInstanceOf(NullPointerException.class);
    }
}
