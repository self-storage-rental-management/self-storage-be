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

    static Instant expiresAt(ReturnCase record) {
        return record.getReturnArrivedAt() == null ? null : record.getReturnArrivedAt().plus(Duration.ofMinutes(30));
    }

    static Instant reviewStartsAt(ReturnCase record) {
        if (!applies(record) || !isOpen(record) || record.getReturnArrivalReviewedAt() != null
            || record.getReturnArrivedAt() == null) return null;
        if (record.getReturnSecondAttemptAt() != null && record.getReturnIdentityVerifiedAt() == null)
            return record.getReturnSecondAttemptAt();
        return expiresAt(record);
    }

    static boolean protectsFromRecovery(ReturnCase record) {
        return applies(record) && isOpen(record) && record.getReturnArrivedAt() != null
            && canRecordArrivalAt(record, record.getReturnArrivedAt())
            && !Boolean.FALSE.equals(record.getReturnArrivalReviewAccepted());
    }

    static boolean canInspect(ReturnCase record, Instant now) {
        return protectsFromRecovery(record) && record.getReturnIdentityVerifiedAt() != null
            && (now.isBefore(expiresAt(record)) || Boolean.TRUE.equals(record.getReturnArrivalReviewAccepted()));
    }
}
