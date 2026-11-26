package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagehub.domain.model.Reservation;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReservationPaymentTermsTests {

    @Test
    void startsRentalFromTheCustomerAppointmentAndFreeSetupDays() {
        Reservation reservation = new Reservation();
        reservation.setAppointmentAt(Instant.parse("2026-10-12T15:00:00Z"));
        reservation.setSetupDaysSnapshot(3);

        ReservationPaymentTerms.apply(
            reservation, 3, Instant.parse("2026-10-12T16:30:00Z")
        );

        assertThat(reservation.getStartDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(reservation.getEndDate()).isEqualTo(LocalDate.of(2027, 1, 15));
        assertThat(reservation.getAppointmentAt()).isEqualTo(Instant.parse("2026-10-12T15:00:00Z"));
    }

    @Test
    void preservesAppointmentOnOrAfterRentalStartDate() {
        Reservation reservation = new Reservation();
        Instant appointment = Instant.parse("2026-10-13T09:30:00Z");
        reservation.setGoodsReviewStatus(com.storagehub.domain.model.GoodsReviewStatus.NOT_REQUIRED);
        reservation.setSetupDaysSnapshot(0);
        reservation.setAppointmentAt(appointment);

        ReservationPaymentTerms.apply(
            reservation, 1, Instant.parse("2026-10-12T03:00:00Z")
        );

        assertThat(reservation.getStartDate()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(reservation.getEndDate()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(reservation.getAppointmentAt()).isEqualTo(appointment);
    }
}
