package com.storagehub.service;

import com.storagehub.api.checkin.CheckInResponse.Attendance;
import com.storagehub.domain.model.Reservation;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

final class ReservationCheckInPolicy {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    static final String LEGACY_ATTENDANCE_TERMS = "HANDOVER_7D_V1";
    static final String ATTENDANCE_TERMS = "HANDOVER_7D_V2";
    static final java.util.List<String> ATTENDANCE_VERSIONS = java.util.List.of(LEGACY_ATTENDANCE_TERMS, ATTENDANCE_TERMS);

    static boolean hasAttendanceTerms(Reservation reservation) {
        String version = reservation.getHandoverTermsVersion();
        return version != null && ATTENDANCE_VERSIONS.contains(version);
    }

    static boolean hasCurrentAttendanceTerms(Reservation reservation) {
        return ATTENDANCE_TERMS.equals(reservation.getHandoverTermsVersion());
    }

    static Instant attendanceDeadline(Instant appointment) {
        return appointment == null ? null : appointment.atZone(VIETNAM_ZONE).toLocalDate()
            .plusDays(CHECK_IN_WINDOW_DAYS).atStartOfDay(VIETNAM_ZONE).toInstant();
    }

    static final Duration ARRIVAL_REVIEW = Duration.ofMinutes(30);
    static final Duration HANDOVER_COMPLETION = Duration.ofHours(72);

    static Instant completionDeadline(Reservation reservation) {
        Instant start = reservation.getFirstHandoverComparisonAt();
        return start == null ? null : start.plus(HANDOVER_COMPLETION);
    }

    static Attendance attendance(Reservation reservation) {
        boolean accepted = hasAttendanceTerms(reservation);
        if (!accepted && reservation.getHandoverArrivedAt() == null && reservation.getFirstHandoverComparisonAt() == null) return null;
        return new Attendance(accepted ? attendanceDeadline(reservation.getAppointmentAt()) : null,
            reservation.getHandoverArrivedAt(),
            reservation.getHandoverTermsVersion(), completionDeadline(reservation), reservation.getArrivalReviewOpenedAt() != null);
    }

    static String displayDeadline(Instant deadline) {
        return java.time.format.DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy")
            .withZone(VIETNAM_ZONE).format(deadline);
    }

    static final long PAYMENT_WINDOW_MINUTES = 15;
    static final long STAFF_REVIEW_HOURS = 6;
    static final long MANAGER_ASSIGNMENT_HOURS = 6;
    static final long CHECK_IN_WINDOW_DAYS = 7;
    static final int ATTENDANCE_FIRST_REMINDER_HOURS = 12;
    static final int FINAL_REMINDER_WINDOW_HOURS = 24;
    private static final int REMINDER_INTERVAL_HOURS = 2;

    // Every attendance deadline falls at midnight in Vietnam.
    static int finalReminderHours(Instant now) {
        int hour = now.atZone(VIETNAM_ZONE).getHour();
        return FINAL_REMINDER_WINDOW_HOURS - hour / REMINDER_INTERVAL_HOURS * REMINDER_INTERVAL_HOURS;
    }

    static int finalReminderMarker(int hours) {
        // Existing rows use +12 for the reminder AFTER the appointment.
        return hours == ATTENDANCE_FIRST_REMINDER_HOURS ? -hours : hours;
    }
    static final long CUSTOMER_RECEIPT_REMINDER_HOURS = 2;
    static final long CUSTOMER_RECEIPT_RELEASE_AFTER_DAYS = 1;
    static final long CUSTOMER_RECEIPT_CONFIRMATION_HOURS = 24 * CUSTOMER_RECEIPT_RELEASE_AFTER_DAYS;

    private ReservationCheckInPolicy() {
    }

    static Window plannedWindow(Instant registeredAt, boolean staffReviewRequired) {
        Instant candidate = registeredAt;
        if (staffReviewRequired) {
            candidate = candidate.plus(Duration.ofHours(STAFF_REVIEW_HOURS));
        }
        candidate = candidate.plus(Duration.ofMinutes(PAYMENT_WINDOW_MINUTES));
        Instant availableAt = candidate.plus(Duration.ofHours(MANAGER_ASSIGNMENT_HOURS));
        return new Window(availableAt, availableAt.plus(Duration.ofDays(CHECK_IN_WINDOW_DAYS)));
    }

    static Instant staffReviewDueAt(Instant submittedAt) {
        return submittedAt.plus(Duration.ofHours(STAFF_REVIEW_HOURS));
    }

    static Instant managerAssignmentDueAt(Instant confirmedAt) {
        return confirmedAt == null ? null : confirmedAt.plus(Duration.ofHours(MANAGER_ASSIGNMENT_HOURS));
    }

    static Instant customerReceiptConfirmationDueAt(Instant handoverAt) {
        return customerReceiptReleaseAt(handoverAt);
    }

    static Instant customerReceiptConfirmationReminderAt(Instant handoverAt) {
        Instant dueAt = customerReceiptConfirmationDueAt(handoverAt);
        return dueAt == null ? null : dueAt.minus(Duration.ofHours(CUSTOMER_RECEIPT_REMINDER_HOURS));
    }

    static Instant customerReceiptReleaseAt(Instant handoverAt) {
        return handoverAt == null
            ? null
            : handoverAt.plus(Duration.ofDays(CUSTOMER_RECEIPT_RELEASE_AFTER_DAYS));
    }

    static boolean handoverExpired(Instant appointmentAt, Instant now) {
        return appointmentAt != null && now.isAfter(attendanceDeadline(appointmentAt).plus(ARRIVAL_REVIEW));
    }

    static Instant firstValidAppointment(Instant now) {
        return now.atZone(VIETNAM_ZONE).toLocalDate().minusDays(CHECK_IN_WINDOW_DAYS - 1)
            .atStartOfDay(VIETNAM_ZONE).toInstant();
    }

    record Window(Instant availableAt, Instant deadlineAt) {
        boolean contains(Instant appointmentAt) {
            if (appointmentAt == null || appointmentAt.isBefore(availableAt)) return false;
            LocalDate appointmentDate = appointmentAt.atZone(VIETNAM_ZONE).toLocalDate();
            LocalDate lastDate = deadlineAt.atZone(VIETNAM_ZONE).toLocalDate();
            return !appointmentDate.isAfter(lastDate);
        }
    }
}
