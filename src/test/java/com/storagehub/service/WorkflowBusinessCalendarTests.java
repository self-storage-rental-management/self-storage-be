package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storagehub.common.api.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkflowBusinessCalendarTests {
    @Test
    void defaultSkipsWeekendAndRetainsVietnamTime() {
        var schedule = WorkflowBusinessCalendar.parse(WorkflowBusinessCalendar.DEFAULT);
        assertThat(schedule.after(Instant.parse("2026-11-20T10:30:00Z"), 1))
            .isEqualTo(Instant.parse("2026-11-23T10:30:00Z"));
    }

    @Test
    void configuredHolidayAndHoursAffectDeadline() {
        var schedule = WorkflowBusinessCalendar.parse("MONDAY,TUESDAY,FRIDAY|08:00|17:00|2026-11-23");
        assertThat(schedule.after(Instant.parse("2026-11-20T13:00:00Z"), 1))
            .isEqualTo(Instant.parse("2026-11-24T10:00:00Z"));
        assertThat(schedule.after(Instant.parse("2026-11-20T00:00:00Z"), 1))
            .isEqualTo(Instant.parse("2026-11-24T01:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "|08:00|17:00|", "MONDAY|17:00|08:00|", "MONDAY|08:00|25:00|", "MONDAY|08:00|17:00|2026-02-30"})
    void rejectsInvalidCalendar(String value) {
        assertThatThrownBy(() -> WorkflowBusinessCalendar.parse(value)).isInstanceOf(ApiException.class);
    }
}
