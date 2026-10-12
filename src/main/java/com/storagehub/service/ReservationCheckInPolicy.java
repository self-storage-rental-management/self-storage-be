package com.storagehub.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

final class ReservationCheckInPolicy {

    static final long PAYMENT_WINDOW_HOURS = 6;
    static final long STAFF_REVIEW_WORKING_HOURS = 9;
    static final long MANAGER_ASSIGNMENT_WORKING_HOURS = 9;
    static final long CHECK_IN_WINDOW_DAYS = 7;
    private static final int OPENING_HOUR = 8;
    private static final int CLOSING_HOUR = 17;
    private static final int LAST_CHECK_IN_MINUTE = 16 * 60 + 30;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private ReservationCheckInPolicy() {
    }

    static Window plannedWindow(Instant registeredAt, boolean staffReviewRequired) {
        ZonedDateTime candidate = registeredAt.atZone(BUSINESS_ZONE);
        if (staffReviewRequired) {
            candidate = addWorkingHours(candidate, STAFF_REVIEW_WORKING_HOURS);
        }
        candidate = candidate.plusHours(PAYMENT_WINDOW_HOURS);
        candidate = addWorkingHours(candidate, MANAGER_ASSIGNMENT_WORKING_HOURS);
        candidate = normalizeCheckInSlot(candidate);
        Instant availableAt = candidate.toInstant();
        return new Window(availableAt, availableAt.plus(Duration.ofDays(CHECK_IN_WINDOW_DAYS)));
    }

    static Instant staffReviewDueAt(Instant submittedAt) {
        return addWorkingHours(
            submittedAt.atZone(BUSINESS_ZONE), STAFF_REVIEW_WORKING_HOURS
        ).toInstant();
    }

    private static ZonedDateTime addWorkingHours(ZonedDateTime value, long hours) {
        ZonedDateTime cursor = value;
        long remainingMinutes = hours * 60;
        while (remainingMinutes > 0) {
            int minuteOfDay = cursor.getHour() * 60 + cursor.getMinute();
            if (minuteOfDay < OPENING_HOUR * 60) {
                cursor = cursor.withHour(OPENING_HOUR).withMinute(0).withSecond(0).withNano(0);
            } else if (minuteOfDay >= CLOSING_HOUR * 60) {
                cursor = nextWorkingDay(cursor);
            }
            long availableMinutes = CLOSING_HOUR * 60L - (cursor.getHour() * 60L + cursor.getMinute());
            long consumedMinutes = Math.min(remainingMinutes, availableMinutes);
            cursor = cursor.plusMinutes(consumedMinutes);
            remainingMinutes -= consumedMinutes;
        }
        return cursor;
    }

    private static ZonedDateTime normalizeCheckInSlot(ZonedDateTime value) {
        ZonedDateTime candidate = value;
        if (candidate.getHour() < OPENING_HOUR) {
            candidate = candidate.withHour(OPENING_HOUR).withMinute(0).withSecond(0).withNano(0);
        } else if (candidate.getMinute() % 30 != 0 || candidate.getSecond() != 0 || candidate.getNano() != 0) {
            int minutesToNextSlot = 30 - (candidate.getMinute() % 30);
            candidate = candidate.plusMinutes(minutesToNextSlot).withSecond(0).withNano(0);
        }
        if (candidate.getHour() * 60 + candidate.getMinute() > LAST_CHECK_IN_MINUTE) {
            candidate = nextWorkingDay(candidate);
        }
        return candidate;
    }

    private static ZonedDateTime nextWorkingDay(ZonedDateTime value) {
        return value.plusDays(1).withHour(OPENING_HOUR).withMinute(0).withSecond(0).withNano(0);
    }

    record Window(Instant availableAt, Instant deadlineAt) {
        boolean contains(Instant appointmentAt) {
            ZonedDateTime localAppointment = appointmentAt.atZone(BUSINESS_ZONE);
            int minuteOfDay = localAppointment.getHour() * 60 + localAppointment.getMinute();
            return !appointmentAt.isBefore(availableAt)
                && !appointmentAt.isAfter(deadlineAt)
                && minuteOfDay >= OPENING_HOUR * 60
                && minuteOfDay <= LAST_CHECK_IN_MINUTE
                && localAppointment.getMinute() % 30 == 0
                && localAppointment.getSecond() == 0
                && localAppointment.getNano() == 0;
        }
    }
}
