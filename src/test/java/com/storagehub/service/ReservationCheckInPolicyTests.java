package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReservationCheckInPolicyTests {

    @Test
    void normalGoodsIncludeFifteenMinutesForPaymentAndSixHoursForAssignment() {
        var window = ReservationCheckInPolicy.plannedWindow(
            Instant.parse("2026-10-12T03:00:00Z"), false
        );

        assertThat(window.availableAt()).isEqualTo(Instant.parse("2026-10-12T09:15:00Z"));
        assertThat(window.deadlineAt()).isEqualTo(Instant.parse("2026-10-19T09:15:00Z"));
    }

    @Test
    void otherGoodsAddSixContinuousHoursForStaffReview() {
        var window = ReservationCheckInPolicy.plannedWindow(
            Instant.parse("2026-10-12T03:00:00Z"), true
        );

        assertThat(window.availableAt()).isEqualTo(Instant.parse("2026-10-12T15:15:00Z"));
    }

    @Test
    void availabilityCanFallAtNightBecauseWarehouseRunsContinuously() {
        var window = ReservationCheckInPolicy.plannedWindow(
            Instant.parse("2026-10-11T19:00:00Z"), false
        );

        assertThat(window.availableAt()).isEqualTo(Instant.parse("2026-10-12T01:15:00Z"));
    }

    @Test
    void acceptsOddMinuteAndNightAppointmentsInsideWindow() {
        var window = ReservationCheckInPolicy.plannedWindow(
            Instant.parse("2026-10-12T10:30:00Z"), true
        );

        assertThat(window.availableAt()).isEqualTo(Instant.parse("2026-10-12T22:45:00Z"));
        assertThat(window.contains(Instant.parse("2026-10-12T22:46:00Z"))).isTrue();
        assertThat(window.contains(Instant.parse("2026-10-13T16:17:00Z"))).isTrue();
    }

    @Test
    void staffReviewAndAssignmentDeadlinesUseElapsedHours() {
        assertThat(ReservationCheckInPolicy.staffReviewDueAt(
            Instant.parse("2026-10-12T10:30:00Z")
        )).isEqualTo(Instant.parse("2026-10-12T16:30:00Z"));
        assertThat(ReservationCheckInPolicy.managerAssignmentDueAt(
            Instant.parse("2026-10-12T10:30:00Z")
        )).isEqualTo(Instant.parse("2026-10-12T16:30:00Z"));
    }
}
