package com.storagehub.service;

import com.storagehub.domain.repo.ReservationRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class HandoverAttendanceScheduler {
    private final ReservationRepository reservations;
    private final CheckInHandoverService handover;

    @Scheduled(fixedDelayString = "${app.handover.attendance-delay-ms:30000}")
    public void processDueReservations() {
        Instant now = Instant.now();
        var due = new java.util.LinkedHashSet<>(reservations.findAttendanceDue(ReservationCheckInPolicy.ATTENDANCE_VERSIONS, ReservationCheckInPolicy.ATTENDANCE_TERMS,
            ReservationCheckInPolicy.firstValidAppointment(now.minus(ReservationCheckInPolicy.ARRIVAL_REVIEW)),
            ReservationCheckInPolicy.firstValidAppointment(now),
            ReservationCheckInPolicy.finalReminderMarker(ReservationCheckInPolicy.finalReminderHours(now)),
            ReservationCheckInPolicy.firstValidAppointment(now.plus(Duration.ofHours(ReservationCheckInPolicy.FINAL_REMINDER_WINDOW_HOURS))),
            now.minus(Duration.ofHours(ReservationCheckInPolicy.ATTENDANCE_FIRST_REMINDER_HOURS))));
        due.addAll(reservations.findUnfinishedHandovers(now.minus(ReservationCheckInPolicy.HANDOVER_COMPLETION)));
        for (var reservationId : due) {
            try {
                handover.processAttendanceDeadline(reservationId);
            } catch (RuntimeException failure) {
                log.warn("Could not process handover deadline for reservation {}", reservationId, failure);
            }
        }
    }
}
