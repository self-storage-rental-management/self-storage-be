package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class DeadlineReminderPolicyTests {
    private final Instant due = Instant.parse("2026-12-01T00:00:00Z");

    @Test void handoverRemindsAt48And24HoursThenEveryTwoHoursInFinal12Hours() {
        assertThat(DeadlineReminderPolicy.handover(due, due.minusSeconds(48 * 3600 + 1))).isNull();
        assertThat(DeadlineReminderPolicy.handover(due, due.minus(Duration.ofHours(48)))).isEqualTo("R48");
        assertThat(DeadlineReminderPolicy.handover(due, due.minus(Duration.ofHours(25)))).isEqualTo("R48");
        assertThat(DeadlineReminderPolicy.handover(due, due.minus(Duration.ofHours(24)))).isEqualTo("R24");
        for (int h = 12; h >= 2; h -= 2) {
            Instant at = due.minus(Duration.ofHours(h));
            assertThat(DeadlineReminderPolicy.handover(due, at)).isEqualTo("R" + h);
            assertThat(DeadlineReminderPolicy.handover(due, at.plusSeconds(60))).isEqualTo("R" + h);
        }
        assertThat(DeadlineReminderPolicy.handover(due, due.minusSeconds(1))).isEqualTo("R2");
        assertThat(DeadlineReminderPolicy.handover(due, due)).isNull();
        assertThat(DeadlineReminderPolicy.handover(due, due.plusSeconds(1))).isNull();
    }

    @Test void assignmentRemindsBeforeDeadlineAndHourlyAfterIt() {
        assertThat(DeadlineReminderPolicy.assignment(due, due.minusSeconds(7201))).isNull();
        assertThat(DeadlineReminderPolicy.assignment(due, due.minusSeconds(7200))).isEqualTo("R2");
        assertThat(DeadlineReminderPolicy.assignment(due, due.minusSeconds(3600))).isEqualTo("R1");
        assertThat(DeadlineReminderPolicy.assignment(due, due.minusSeconds(1800))).isEqualTo("R30M");
        assertThat(DeadlineReminderPolicy.assignment(due, due)).isEqualTo("O0");
        assertThat(DeadlineReminderPolicy.assignment(due, due.plusSeconds(3599))).isEqualTo("O0");
        assertThat(DeadlineReminderPolicy.assignment(due, due.plusSeconds(3600))).isEqualTo("O1");
        assertThat(DeadlineReminderPolicy.assignment(due, due.plusSeconds(7200))).isEqualTo("O2");
    }

    @Test void handoverNotificationWarnsCustomerOfAutomaticCancellationAndStopsAtDeadline() {
        var facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", UUID.randomUUID());
        var customer = new User();
        ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());
        var reservation = new Reservation();
        reservation.setFacility(facility);
        reservation.setCustomer(customer);
        reservation.setReservationCode("RSV-REMINDER");
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        reservation.setAssignedUnit(new StorageUnit());
        reservation.setHandoverArrivedAt(due.minus(Duration.ofHours(72)));
        var checkIn = new CheckIn();
        ReflectionTestUtils.setField(checkIn, "id", UUID.randomUUID());
        checkIn.setReservation(reservation);
        checkIn.setStatus(CheckInStatus.payment_collected);
        var notifications = mock(NotificationService.class);
        var jdbc = mock(JdbcTemplate.class);
        var scheduler = new WorkflowSlaScheduler(mock(EntityManager.class), null, jdbc,
            mock(UserRepository.class), notifications, mock(AuditLogService.class), null, null, null, null);
        Instant now = due.minus(Duration.ofHours(24));
        Object deadline = ReflectionTestUtils.invokeMethod(scheduler, "checkInDeadline", checkIn, now, null);
        assertThat(deadline).isNotNull();
        ReflectionTestUtils.invokeMethod(scheduler, "deliver", deadline, now);
        verify(notifications).createNotification(eq(customer.getId()), eq(NotificationType.CHECKIN),
            contains("RSV-REMINDER"), contains("đơn sẽ tự động bị hủy"), eq(reservation.getId()));
        clearInvocations(notifications);
        ReflectionTestUtils.invokeMethod(scheduler, "deliver", deadline, due);
        verifyNoInteractions(notifications);
        checkIn.setStatus(CheckInStatus.cancelled);
        assertThat((Object) ReflectionTestUtils.invokeMethod(scheduler, "checkInDeadline", checkIn, now, null)).isNull();
    }
}
