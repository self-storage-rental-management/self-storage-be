package com.storagehub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.repo.SystemSettingRepository;
import com.storagehub.time.BusinessTime;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkflowBusinessCalendar {
    public static final String KEY = "workflowBusinessCalendar";
    public static final String DEFAULT = "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY|00:00|24:00|";
    private final SystemSettingRepository settings;
    private final ObjectMapper mapper;

    public Schedule current() {
        String value = settings.findBySettingKey(KEY).map(setting -> {
            try {
                return mapper.readValue(setting.getValue(), String.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new IllegalStateException("Invalid workflow business calendar setting", invalid);
            }
        }).orElse(DEFAULT);
        return parse(value);
    }

    public static Schedule parse(String value) {
        try {
            var fields = value.strip().split("\\|", -1);
            if (fields.length != 4 || !fields[1].matches("[0-2][0-9]:[0-5][0-9]")
                || !fields[2].matches("[0-2][0-9]:[0-5][0-9]")) throw new IllegalArgumentException();
            var days = Arrays.stream(fields[0].split(",", -1)).map(String::trim)
                .map(DayOfWeek::valueOf).collect(Collectors.toUnmodifiableSet());
            var opens = LocalTime.parse(fields[1]);
            boolean midnightClose = fields[2].equals("24:00");
            var closes = midnightClose ? LocalTime.MIDNIGHT : LocalTime.parse(fields[2]);
            if (!midnightClose && !closes.isAfter(opens)) throw new IllegalArgumentException();
            var holidays = fields[3].isBlank() ? Set.<LocalDate>of() : Arrays.stream(fields[3].split(",", -1))
                .map(String::trim).map(LocalDate::parse).collect(Collectors.toUnmodifiableSet());
            return new Schedule(days, opens, closes, midnightClose, holidays);
        } catch (RuntimeException invalid) {
            throw ApiExceptions.validation("Lịch SLA phải theo mẫu THỨ,THỨ|HH:mm|HH:mm|yyyy-MM-dd,yyyy-MM-dd, dùng tên thứ tiếng Anh.", KEY);
        }
    }

    public record Schedule(Set<DayOfWeek> days, LocalTime opens, LocalTime closes, boolean midnightClose,
        Set<LocalDate> holidays) {
        public Instant afterWorkingHours(Instant start, long hours) {
            var date = start.atZone(BusinessTime.ZONE).toLocalDate();
            var remaining = java.time.Duration.ofHours(hours);
            while (!remaining.isZero()) {
                if (days.contains(date.getDayOfWeek()) && !holidays.contains(date)) {
                    Instant open = date.atTime(opens).atZone(BusinessTime.ZONE).toInstant();
                    Instant close = (midnightClose ? date.plusDays(1).atStartOfDay() : date.atTime(closes))
                        .atZone(BusinessTime.ZONE).toInstant();
                    Instant cursor = start.isAfter(open) ? start : open;
                    if (cursor.isBefore(close)) {
                        var available = java.time.Duration.between(cursor, close);
                        if (remaining.compareTo(available) <= 0) return cursor.plus(remaining);
                        remaining = remaining.minus(available);
                    }
                }
                date = date.plusDays(1);
            }
            return start;
        }

        public Instant after(Instant start, int daysToCount) {
            var local = start.atZone(BusinessTime.ZONE);
            var date = local.toLocalDate();
            while (daysToCount > 0) {
                date = date.plusDays(1);
                if (days.contains(date.getDayOfWeek()) && !holidays.contains(date)) daysToCount--;
            }
            LocalTime time = local.toLocalTime();
            if (time.isBefore(opens)) time = opens;
            if (!midnightClose && time.isAfter(closes)) time = closes;
            return date.atTime(time).atZone(BusinessTime.ZONE).toInstant();
        }
    }
}
