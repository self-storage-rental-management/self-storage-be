package com.storagehub.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Builds the authoritative rental timeline from the customer's selected
 * handover appointment.
 *
 * <p>The appointment is the business date used for the rental timeline. The
 * free preparation period is added to that appointment and produces the paid
 * rental start date. The actual check-in confirmation remains separate audit
 * data and must not move these dates.</p>
 */
final class RentalTimelinePolicy {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private RentalTimelinePolicy() {
    }

    static Dates fromCustomerAppointment(Instant appointmentAt, int freeSetupDays, int rentalMonths) {
        if (appointmentAt == null) throw new IllegalArgumentException("appointmentAt is required");
        if (freeSetupDays < 0) throw new IllegalArgumentException("freeSetupDays cannot be negative");
        if (rentalMonths < 1) throw new IllegalArgumentException("rentalMonths must be positive");

        Instant freeSetupEndsAt = appointmentAt.plus(Duration.ofDays(freeSetupDays));
        LocalDate paidRentalStart = appointmentAt.atZone(BUSINESS_ZONE)
            .toLocalDate()
            .plusDays(freeSetupDays);
        return new Dates(
            freeSetupEndsAt,
            paidRentalStart,
            paidRentalStart.plusMonths(rentalMonths)
        );
    }

    /**
     * Compatibility alias for callers compiled against the previous policy
     * name. The value is now deliberately interpreted as the customer-selected
     * appointment, not the instant at which the confirmation button was pressed.
     */
    @Deprecated
    static Dates fromCustomerReceipt(Instant appointmentAt, int freeSetupDays, int rentalMonths) {
        return fromCustomerAppointment(appointmentAt, freeSetupDays, rentalMonths);
    }

    record Dates(Instant freeSetupEndsAt, LocalDate paidRentalStart, LocalDate contractEndDate) {
    }
}
