package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import com.storagehub.service.calendar.ServiceCalendarPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReservationCheckInPolicyTests {

    @ParameterizedTest
    @CsvSource({
        "2026-10-12T02:00,2026-10-14T08:00",
        "2026-10-12T08:00,2026-10-14T08:00",
        "2026-10-12T09:00,2026-10-14T15:00",
        "2026-10-12T10:00,2026-10-14T16:00",
        "2026-10-12T12:00,2026-10-15T08:00",
        "2026-10-12T16:30,2026-10-15T08:00",
        "2026-10-12T17:38,2026-10-15T08:00"
    })
    void otherGoodsFollowTheSevenTeamPolicyExamples(String submitted, String expected) {
        var window = ReservationCheckInPolicy.plannedWindow(
            LocalDateTime.parse(submitted).atZone(ServiceCalendarPolicy.ZONE).toInstant(), true);
        assertThat(window.availableAt()).isEqualTo(
            LocalDateTime.parse(expected).atZone(ServiceCalendarPolicy.ZONE).toInstant());
    }

    @Test
    void normalGoodsIncludeSixHoursForPaymentAndOneWorkingDayForAssignment() {
        var window = ReservationCheckInPolicy.plannedWindow(
            Instant.parse("2026-10-12T03:00:00Z"), false
        );

        assertThat(window.availableAt()).isEqualTo(Instant.parse("2026-10-13T09:00:00Z"));
        assertThat(window.deadlineAt()).isEqualTo(Instant.parse("2026-10-20T09:00:00Z"));
    }

    @Test
    void otherGoodsAddOneWorkingDayForStaffReview() {
        var window = ReservationCheckInPolicy.plannedWindow(
            Instant.parse("2026-10-12T03:00:00Z"), true
        );

        assertThat(window.availableAt()).isEqualTo(Instant.parse("2026-10-14T09:00:00Z"));
    }

    @Test
    void earlyMorningAvailabilityMovesToEightInTheMorning() {
        var window = ReservationCheckInPolicy.plannedWindow(
            Instant.parse("2026-10-11T19:00:00Z"), false
        );

        assertThat(window.availableAt()).isEqualTo(Instant.parse("2026-10-13T01:00:00Z"));
    }

    @Test
    void afterHoursAvailabilityMovesToEightTheNextMorningAndRejectsNightAppointments() {
        var window = ReservationCheckInPolicy.plannedWindow(
            Instant.parse("2026-10-12T10:30:00Z"), true
        );

        assertThat(window.availableAt()).isEqualTo(Instant.parse("2026-10-15T01:00:00Z"));
        assertThat(window.contains(Instant.parse("2026-10-15T16:30:00Z"))).isFalse();
        assertThat(window.contains(Instant.parse("2026-10-15T03:00:00Z"))).isTrue();
    }

    @Test
    void lastCheckInSlotIsFourThirtyAndFiveOClockIsRejected() {
        var window = ReservationCheckInPolicy.plannedWindow(
            Instant.parse("2026-10-12T03:00:00Z"), false
        );

        assertThat(window.contains(Instant.parse("2026-10-13T09:30:00Z"))).isTrue();
        assertThat(window.contains(Instant.parse("2026-10-13T10:00:00Z"))).isFalse();
    }

    @Test
    void staffReviewDueAtCountsOnlyWorkingHours() {
        assertThat(ReservationCheckInPolicy.staffReviewDueAt(
            Instant.parse("2026-10-12T10:30:00Z")
        )).isEqualTo(Instant.parse("2026-10-13T10:00:00Z"));
    }
}
