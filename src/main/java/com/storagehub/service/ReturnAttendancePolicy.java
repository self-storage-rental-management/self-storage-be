package com.storagehub.service;

import com.storagehub.domain.model.ReturnCase;
import com.storagehub.domain.model.ReturnCaseStatus;
import com.storagehub.domain.model.ReturnType;
import java.time.Duration;
import java.time.Instant;

final class ReturnAttendancePolicy {
    private ReturnAttendancePolicy() {}

    static boolean isScheduledRentalReturn(ReturnCase record) {
        return !record.isSetupExitRequested()
            && (record.getReturnType() == ReturnType.NORMAL || record.getReturnType() == ReturnType.EARLY_RETURN);
    }

    static boolean isDeadlineMismatchReturn(ReturnCase record) {
        return record.getReturnType() == ReturnType.INVENTORY_MISMATCH
            && record.getRental().getInventoryRemovalDeadlineAt() != null;
    }

    static boolean applies(ReturnCase record) {
        return isScheduledRentalReturn(record) || isDeadlineMismatchReturn(record);
    }

    static boolean canRecordArrivalAt(ReturnCase record, Instant time) {
        Instant deadline = isDeadlineMismatchReturn(record) ? record.getRental().getInventoryRemovalDeadlineAt()
            : record.getRecoveryEligibleAt();
        return deadline == null || time.isBefore(deadline);
    }

    static boolean isOpen(ReturnCase record) {
        return record.getInspectedAt() == null && record.getNoShowAt() == null
            && (record.getStatus() == ReturnCaseStatus.requested || record.getStatus() == ReturnCaseStatus.scheduled);
    }

    static boolean isPendingArrivalReview(ReturnCase record) {
        return record != null && IntakeTimelineCalculator.REVIEW_PENDING.equals(record.getReturnArrivalReviewStatus());
    }

    static boolean isFacilityExceptionAllowed(ReturnCase record) {
        return record != null
            && IntakeTimelineCalculator.REVIEW_FACILITY_EXCEPTION.equals(record.getReturnArrivalReviewStatus())
            && !Boolean.FALSE.equals(record.getReturnArrivalReviewAccepted());
    }

    static Instant expiresAt(ReturnCase record) {
        Instant start = record.getReturnReverificationStartedAt();
        if (start == null) start = record.getReturnArrivedAt();
        return start == null ? null : start.plus(Duration.ofMinutes(30));
    }

    static Instant reviewStartsAt(ReturnCase record) {
        if (!applies(record) || !isOpen(record)) return null;
        if (isPendingArrivalReview(record)) {
            return record.getReturnArrivalRecordedAt() != null
                ? record.getReturnArrivalRecordedAt() : record.getReturnLateArrivalClaimedAt();
        }
        if (record.getReturnArrivedAt() == null) return null;
        if (Boolean.TRUE.equals(record.getReturnArrivalReviewAccepted())) {
            return record.getPostReviewHandoverDueAt() == null ? record.getReturnArrivalReviewedAt() : record.getPostReviewHandoverDueAt();
        }
        if (record.getReturnArrivalReviewedAt() != null) return null;
        if (record.getReturnSecondAttemptAt() != null && record.getReturnIdentityVerifiedAt() == null)
            return record.getReturnSecondAttemptAt();
        return expiresAt(record);
    }

    static boolean protectsFromRecovery(ReturnCase record) {
        if (!applies(record) || !isOpen(record)) return false;
        if (Boolean.FALSE.equals(record.getReturnArrivalReviewAccepted())) return false;
        if (isPendingArrivalReview(record) || isFacilityExceptionAllowed(record)) return true;
        return record.getReturnArrivedAt() != null && canRecordArrivalAt(record, record.getReturnArrivedAt());
    }

    static boolean canInspect(ReturnCase record, Instant now) {
        Instant appointment = record.getPostReviewHandoverAppointmentAt();
        Instant followUp = record.getPostReviewHandoverDueAt();
        return protectsFromRecovery(record) && record.getReturnIdentityVerifiedAt() != null
            && (Boolean.TRUE.equals(record.getReturnArrivalReviewAccepted())
                ? (appointment == null || !now.isBefore(appointment)) && (followUp == null || now.isBefore(followUp))
                : now.isBefore(expiresAt(record)));
    }
}
