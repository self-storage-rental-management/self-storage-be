package com.storagehub.service.renewal;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class RenewalSigningDeadlineTests {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,3","3,5","6,8","12,14","24,14","2147483647,14"})
    void packageWindowGrowsWithMonthsAndIsCapped(int months,int days) {
        assertThat(RenewalSigningDeadline.daysForMonths(months)).isEqualTo(days);
    }
    @Test void acceptedSnapshotControlsWindowAndOverdueCapsIt() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var terms=mapper.readValue("{\"rentalMonths\":12,\"signingWindowDays\":14}", com.storagehub.api.renewal.RenewalQuoteResponse.Terms.class);
        Instant paid=Instant.parse("2026-10-01T08:00:00Z");
        assertThat(RenewalSigningDeadline.calculate(paid,paid.plus(Duration.ofDays(30)),terms)).isEqualTo(paid.plus(Duration.ofDays(14)));
        assertThat(RenewalSigningDeadline.calculate(paid,paid.plus(Duration.ofDays(2)),terms)).isEqualTo(paid.plus(Duration.ofDays(2)));
        var legacy=mapper.readValue("{\"rentalMonths\":12}", com.storagehub.api.renewal.RenewalQuoteResponse.Terms.class);
        assertThat(RenewalSigningDeadline.window(legacy)).isEqualTo(Duration.ofDays(3));
    }
    @Test void preparationRetryUsesSameAcceptedWindow() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var payment=org.mockito.Mockito.mock(com.storagehub.domain.model.Payment.class,org.mockito.Mockito.RETURNS_DEEP_STUBS);
        Instant paid=Instant.parse("2026-10-01T08:00:00Z");
        org.mockito.Mockito.when(payment.getPaidAt()).thenReturn(paid);
        org.mockito.Mockito.when(payment.getRenewalRevision().getQuote().getTermsJson()).thenReturn("{\"rentalMonths\":6,\"signingWindowDays\":8}");
        assertThat(RenewalPreparationResolution.deadline(payment,null,mapper)).isEqualTo(paid.plus(Duration.ofDays(8)));
        org.mockito.Mockito.when(payment.getRenewalRevision().getQuote().getTermsJson()).thenReturn("invalid");
        assertThatThrownBy(()->RenewalPreparationResolution.deadline(payment,null,mapper)).hasMessageContaining("reconciliation");
    }
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
