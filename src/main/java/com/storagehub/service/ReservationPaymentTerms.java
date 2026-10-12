package com.storagehub.service;

import com.storagehub.domain.model.Reservation;
import java.time.Instant;
import java.time.ZoneId;

/** Applies the rental period that becomes authoritative when the deposit is paid. */
final class ReservationPaymentTerms {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private ReservationPaymentTerms() {
    }

    static void apply(Reservation reservation, int rentalMonths, Instant paidAt) {
        if (rentalMonths < 1) {
            throw new IllegalArgumentException("rentalMonths must be at least 1");
        }
        ReservationCheckInPolicy.Window window = ReservationCheckInPolicy.plannedWindow(
            reservation.getCreatedAt() != null ? reservation.getCreatedAt() : paidAt,
            reservation.getGoodsReviewStatus() != com.storagehub.domain.model.GoodsReviewStatus.NOT_REQUIRED
        );
        java.time.LocalDate startDate = window.availableAt().atZone(BUSINESS_ZONE).toLocalDate();
        reservation.setStartDate(startDate);
        reservation.setEndDate(startDate.plusMonths(rentalMonths));

        Instant appointmentAt = reservation.getAppointmentAt();
        if (appointmentAt != null && !window.contains(appointmentAt)) {
            reservation.setAppointmentAt(null);
        }
    }
}
