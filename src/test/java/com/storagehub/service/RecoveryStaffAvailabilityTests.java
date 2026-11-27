package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class RecoveryStaffAvailabilityTests {
    private final EntityManager em = mock(EntityManager.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RecoveryStaffAvailability availability = new RecoveryStaffAvailability(em, jdbc, users);
    private final UUID staff = UUID.randomUUID();
    private final Instant start = Instant.parse("2026-11-27T03:00:00Z");
    private final Instant end = start.plus(Duration.ofHours(48));
    private final ViolationRecovery own = identified(new ViolationRecovery());
    private final List<ViolationRecovery> recoveries = new ArrayList<>();
    private final List<MaintenanceTask> tasks = new ArrayList<>();
    private final List<CheckIn> checkIns = new ArrayList<>();
    private final List<ReturnCase> returns = new ArrayList<>();

    @BeforeEach void setUp() {
        own.setRental(identified(new Rental()));
        query(ViolationRecovery.class, recoveries);
        query(MaintenanceTask.class, tasks);
        query(CheckIn.class, checkIns);
        query(ReturnCase.class, returns);
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(0L);
    }

    @Test void rejectsOverlappingRecoveryAndAllowsAdjacentWindows() {
        var other = new ViolationRecovery();
        other.setScheduledAt(start.plusSeconds(1));
        other.setDeadlineAt(end.plusSeconds(1));
        recoveries.add(other);
        assertThat(available()).isFalse();
        other.setScheduledAt(end);
        assertThat(available()).isTrue();
        other.setScheduledAt(start.minusSeconds(3600));
        other.setDeadlineAt(start);
        assertThat(available()).isTrue();
    }

    @Test void rejectsMaintenanceInProgressEvenWhenItsDeadlineHasPassed() {
        var task = new MaintenanceTask();
        task.setStatus(MaintenanceTaskStatus.in_progress);
        task.setStartedAt(start.minusSeconds(3600));
        task.setDeadlineAt(start.minusSeconds(1));
        tasks.add(task);
        assertThat(available()).isFalse();
    }

    @Test void rejectsAHandOverOrInventoryAppointmentInsideTheRecoveryWindow() {
        var checkIn = new CheckIn();
        var reservation = new Reservation();
        reservation.setAppointmentAt(start.plusSeconds(1800));
        checkIn.setReservation(reservation);
        checkIns.add(checkIn);
        assertThat(available()).isFalse();
        checkIn.setStatus(CheckInStatus.setup_completed);
        checkIn.setInventoryAppointmentAt(end);
        assertThat(available()).isTrue();
        checkIn.setInventoryAppointmentAt(end.minusSeconds(1));
        assertThat(available()).isFalse();
    }

    @Test void rejectsReturnHandoverAndRenewalSigningAppointments() {
        var returned = new ReturnCase();
        returned.setRental(identified(new Rental()));
        returned.setPostReviewHandoverStaffId(staff);
        returned.setPostReviewHandoverAppointmentAt(start);
        returns.add(returned);
        assertThat(available()).isFalse();
        returns.clear();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(1L);
        assertThat(available()).isFalse();
    }

    @Test void worksWhenTheOptionalSigningCalendarIsNotInstalled() {
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn(false);
        assertThat(available()).isTrue();
        verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), any(), any(), any());
    }

    @Test void locksStaffInStableOrderAndOnlyOnceForConcurrentAssignments() {
        var second = UUID.randomUUID();
        when(users.findByIdForUpdate(any())).thenReturn(Optional.of(new User()));
        availability.lock(List.of(staff, second, staff));
        var ordered = List.of(staff, second).stream().sorted().toList();
        var calls = inOrder(users);
        for (var id : ordered) calls.verify(users).findByIdForUpdate(id);
        verifyNoMoreInteractions(users);
    }

    private boolean available() { return availability.available(staff, own, start, end); }
    @SuppressWarnings("unchecked")
    private <T> void query(Class<T> type, List<T> result) {
        TypedQuery<T> query = mock(TypedQuery.class, RETURNS_SELF);
        when(em.createQuery(anyString(), eq(type))).thenReturn(query);
        when(query.getResultList()).thenReturn(result);
    }
    private static <T extends BaseEntity> T identified(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }
}
