package com.storagehub.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ServiceCalendarPolicyTests {
    @ParameterizedTest
    @CsvSource({
        "05:30,2026-10-12T05:30",
        "10:00,2026-10-12T10:00",
        "10:00:00.000000001,2026-10-12T10:30",
        "10:01,2026-10-12T10:30",
        "10:29,2026-10-12T10:30",
        "10:31,2026-10-12T11:00",
        "16:30,2026-10-12T16:30",
        "16:30:01,2026-10-12T17:00",
        "17:00,2026-10-12T17:00",
        "23:30,2026-10-12T23:30",
        "23:30:01,2026-10-13T00:00"
    })
    void roundsUpToTheNextHalfHourAcrossThe24HourSchedule(String time, String expected) {
        assertThat(ServiceCalendarPolicy.nextAppointmentAt(at("2026-10-12T" + time)))
            .isEqualTo(at(expected));
    }

    @Test
    void workingHoursPreserveSubMinutePrecisionAcrossMidnight() {
        assertThat(ServiceCalendarPolicy.addWorkingHours(at("2026-10-12T16:30:45"), 9))
            .isEqualTo(at("2026-10-13T01:30:45"));
    }

    @Test
    void weekendDaysRemainWorkingDays() {
        assertThat(ServiceCalendarPolicy.addWorkingHours(at("2026-10-17T16:00"), 2))
            .isEqualTo(at("2026-10-17T18:00"));
        assertThat(ServiceCalendarPolicy.nextAppointmentAt(at("2026-10-18T10:00")))
            .isEqualTo(at("2026-10-18T10:00"));
        assertThat(ServiceCalendarPolicy.isAppointmentStart(at("2026-10-18T10:00"))).isTrue();
    }

    @Test
    void onlyExactHalfHourStartsAreValidAtAnyTimeOfDay() {
        assertThat(ServiceCalendarPolicy.isAppointmentStart(at("2026-10-12T00:00"))).isTrue();
        assertThat(ServiceCalendarPolicy.isAppointmentStart(at("2026-10-12T08:00"))).isTrue();
        assertThat(ServiceCalendarPolicy.isAppointmentStart(at("2026-10-12T16:30"))).isTrue();
        assertThat(ServiceCalendarPolicy.isAppointmentStart(at("2026-10-12T08:00:01"))).isFalse();
        assertThat(ServiceCalendarPolicy.isAppointmentStart(at("2026-10-12T17:00"))).isTrue();
        assertThat(ServiceCalendarPolicy.isAppointmentStart(at("2026-10-12T23:30"))).isTrue();
        assertThat(ServiceCalendarPolicy.isAppointmentStart(at("2026-10-12T23:59"))).isFalse();
        assertThat(ServiceCalendarPolicy.isAppointmentStart(null)).isFalse();
    }

    private static java.time.Instant at(String local) {
        return LocalDateTime.parse(local).atZone(ServiceCalendarPolicy.ZONE).toInstant();
    }
}
