package com.storagehub.service;

import com.storagehub.service.calendar.ServiceCalendarPolicy;
import java.time.Duration;
import java.time.Instant;

final class ReservationCheckInPolicy {

    static final long PAYMENT_WINDOW_HOURS = 6;
    static final long STAFF_REVIEW_WORKING_HOURS = 9;
    static final long MANAGER_ASSIGNMENT_WORKING_HOURS = 9;
    static final long CHECK_IN_WINDOW_DAYS = 7;

    private ReservationCheckInPolicy() {
    }

    static Window plannedWindow(Instant registeredAt, boolean staffReviewRequired) {
        Instant candidate = registeredAt;
        if (staffReviewRequired) {
            candidate = ServiceCalendarPolicy.addWorkingHours(candidate, STAFF_REVIEW_WORKING_HOURS);
        }
        candidate = candidate.plus(Duration.ofHours(PAYMENT_WINDOW_HOURS));
        candidate = ServiceCalendarPolicy.addWorkingHours(candidate, MANAGER_ASSIGNMENT_WORKING_HOURS);
        Instant availableAt = ServiceCalendarPolicy.nextAppointmentAt(candidate);
        return new Window(availableAt, availableAt.plus(Duration.ofDays(CHECK_IN_WINDOW_DAYS)));
    }

    static Instant staffReviewDueAt(Instant submittedAt) {
        return ServiceCalendarPolicy.addWorkingHours(submittedAt, STAFF_REVIEW_WORKING_HOURS);
    }

    record Window(Instant availableAt, Instant deadlineAt) {
        boolean contains(Instant appointmentAt) {
            return ServiceCalendarPolicy.isAppointmentStart(appointmentAt)
                && !appointmentAt.isBefore(availableAt)
                && !appointmentAt.isAfter(deadlineAt);
        }
    }
}
