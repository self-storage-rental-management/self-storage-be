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

    record Window(Instant availableAt, Instant deadlineAt) {
        boolean contains(Instant appointmentAt) {
            if (appointmentAt == null) return false;
            LocalDate appointmentDate = appointmentAt.atZone(VIETNAM_ZONE).toLocalDate();
            LocalDate firstDate = availableAt.atZone(VIETNAM_ZONE).toLocalDate();
            LocalDate lastDate = deadlineAt.atZone(VIETNAM_ZONE).toLocalDate();
            return !appointmentDate.isBefore(firstDate) && !appointmentDate.isAfter(lastDate);
        }
    }
}
