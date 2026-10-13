package com.storagehub.service.calendar;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/** Team service hours. A valid start time does not certify staff or facility availability. */
public final class ServiceCalendarPolicy {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final LocalTime OPEN = LocalTime.of(8, 0);
    public static final LocalTime CLOSE = LocalTime.of(17, 0);
    public static final LocalTime LAST_APPOINTMENT_START = LocalTime.of(16, 30);
    public static final int SLOT_STEP_MINUTES = 30;
    public static final List<DayOfWeek> WORKING_DAYS = List.of(DayOfWeek.values());

    private ServiceCalendarPolicy() {}

    public static Instant addWorkingHours(Instant value, long hours) {
        Objects.requireNonNull(value, "value");
        if (hours < 0) throw new IllegalArgumentException("Working hours must be non-negative");
        ZonedDateTime cursor = value.atZone(ZONE);
        Duration remaining = Duration.ofHours(hours);
        while (!remaining.isZero()) {
            if (cursor.toLocalTime().isBefore(OPEN)) cursor = cursor.with(OPEN);
            else if (!cursor.toLocalTime().isBefore(CLOSE)) cursor = nextDay(cursor);
            Duration available = Duration.between(cursor, cursor.with(CLOSE));
            Duration consumed = remaining.compareTo(available) <= 0 ? remaining : available;
            cursor = cursor.plus(consumed);
            remaining = remaining.minus(consumed);
        }
        return cursor.toInstant();
    }

    public static Instant nextAppointmentAt(Instant value) {
        ZonedDateTime candidate = Objects.requireNonNull(value, "value").atZone(ZONE);
        if (candidate.toLocalTime().isBefore(OPEN)) candidate = candidate.with(OPEN);
        else if (candidate.getMinute() % SLOT_STEP_MINUTES != 0
            || candidate.getSecond() != 0 || candidate.getNano() != 0) {
            candidate = candidate.plusMinutes(SLOT_STEP_MINUTES - candidate.getMinute() % SLOT_STEP_MINUTES)
                .withSecond(0).withNano(0);
        }
        if (candidate.toLocalTime().isAfter(LAST_APPOINTMENT_START)) candidate = nextDay(candidate);
        return candidate.toInstant();
    }

    public static boolean isAppointmentStart(Instant value) {
        if (value == null) return false;
        var local = value.atZone(ZONE);
        return !local.toLocalTime().isBefore(OPEN)
            && !local.toLocalTime().isAfter(LAST_APPOINTMENT_START)
            && local.getMinute() % SLOT_STEP_MINUTES == 0 && local.getSecond() == 0 && local.getNano() == 0;
    }

    private static ZonedDateTime nextDay(ZonedDateTime value) {
        return value.plusDays(1).with(OPEN);
    }
}
