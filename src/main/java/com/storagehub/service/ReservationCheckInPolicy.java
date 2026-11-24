package com.storagehub.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

final class ReservationCheckInPolicy {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    static final long PAYMENT_WINDOW_MINUTES = 15;
    static final long STAFF_REVIEW_HOURS = 6;
    static final long MANAGER_ASSIGNMENT_HOURS = 6;
    static final long CHECK_IN_WINDOW_DAYS = 7;
    static final long CUSTOMER_RECEIPT_REMINDER_HOURS = 2;
    static final long CUSTOMER_RECEIPT_RELEASE_AFTER_DAYS = 1;
    static final long CUSTOMER_RECEIPT_CONFIRMATION_HOURS = 24 * CUSTOMER_RECEIPT_RELEASE_AFTER_DAYS;

    private ReservationCheckInPolicy() {
    }

    static Window plannedWindow(Instant registeredAt, boolean staffReviewRequired) {
        Instant candidate = registeredAt;
        if (staffReviewRequired) {
            candidate = candidate.plus(Duration.ofHours(STAFF_REVIEW_HOURS));
        }
        candidate = candidate.plus(Duration.ofMinutes(PAYMENT_WINDOW_MINUTES));
        Instant availableAt = candidate.plus(Duration.ofHours(MANAGER_ASSIGNMENT_HOURS));
        return new Window(availableAt, availableAt.plus(Duration.ofDays(CHECK_IN_WINDOW_DAYS)));
    }

    static Instant staffReviewDueAt(Instant submittedAt) {
        return submittedAt.plus(Duration.ofHours(STAFF_REVIEW_HOURS));
    }

    static Instant managerAssignmentDueAt(Instant confirmedAt) {
        return confirmedAt == null ? null : confirmedAt.plus(Duration.ofHours(MANAGER_ASSIGNMENT_HOURS));
    }

    static Instant customerReceiptConfirmationDueAt(Instant handoverAt) {
        return customerReceiptReleaseAt(handoverAt);
    }

    static Instant customerReceiptConfirmationReminderAt(Instant handoverAt) {
        Instant dueAt = customerReceiptConfirmationDueAt(handoverAt);
        return dueAt == null ? null : dueAt.minus(Duration.ofHours(CUSTOMER_RECEIPT_REMINDER_HOURS));
    }

    static Instant customerReceiptReleaseAt(Instant handoverAt) {
        return handoverAt == null
            ? null
            : handoverAt.plus(Duration.ofDays(CUSTOMER_RECEIPT_RELEASE_AFTER_DAYS));
    }

    static boolean handoverExpired(Instant appointmentAt, Instant now) {
        return appointmentAt != null && appointmentAt.isBefore(firstValidAppointment(now));
    }

    static Instant firstValidAppointment(Instant now) {
        return now.atZone(VIETNAM_ZONE).toLocalDate().minusDays(CHECK_IN_WINDOW_DAYS - 1)
            .atStartOfDay(VIETNAM_ZONE).toInstant();
    }

    record Window(Instant availableAt, Instant deadlineAt) {
        boolean contains(Instant appointmentAt) {
            if (appointmentAt == null || appointmentAt.isBefore(availableAt)) return false;
            LocalDate appointmentDate = appointmentAt.atZone(VIETNAM_ZONE).toLocalDate();
            LocalDate lastDate = deadlineAt.atZone(VIETNAM_ZONE).toLocalDate();
            return !appointmentDate.isAfter(lastDate);
        }
    }
}
