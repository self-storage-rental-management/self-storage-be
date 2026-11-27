package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RentalTimelinePolicyTests {

    @Test
    void calculatesAllRentalDatesFromTheCustomerSelectedAppointment() {
        Instant appointmentAt = Instant.parse("2026-11-24T08:40:56Z");

        var dates = RentalTimelinePolicy.fromCustomerAppointment(appointmentAt, 3, 6);

        assertThat(dates.freeSetupEndsAt()).isEqualTo(Instant.parse("2026-11-27T08:40:56Z"));
        assertThat(dates.paidRentalStart()).isEqualTo(LocalDate.of(2026, 11, 27));
        assertThat(dates.contractEndDate()).isEqualTo(LocalDate.of(2027, 5, 27));
    }

    @Test
    void usesVietnameseCalendarDateWhenAppointmentCrossesUtcMidnight() {
        Instant appointmentAt = Instant.parse("2026-11-24T17:00:00Z");

        var dates = RentalTimelinePolicy.fromCustomerAppointment(appointmentAt, 3, 3);

        assertThat(dates.paidRentalStart()).isEqualTo(LocalDate.of(2026, 11, 28));
        assertThat(dates.freeSetupEndsAt()).isEqualTo(Instant.parse("2026-11-27T17:00:00Z"));
    }
}
