package com.storagehub.service;

import com.storagehub.domain.model.ReturnCase;
import com.storagehub.domain.model.ReturnCaseStatus;
import com.storagehub.domain.model.ReturnType;
import java.time.Duration;
import java.time.Instant;

final class MismatchReturnAttendance {
    private MismatchReturnAttendance() {}

    static boolean applies(ReturnCase record) {
        return record.getReturnType() == ReturnType.INVENTORY_MISMATCH
            && record.getRental().getInventoryRemovalDeadlineAt() != null;
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
            && record.getReturnArrivedAt().isBefore(record.getRental().getInventoryRemovalDeadlineAt())
            && !Boolean.FALSE.equals(record.getReturnArrivalReviewAccepted());
    }

    static boolean canInspect(ReturnCase record, Instant now) {
        return protectsFromRecovery(record) && record.getReturnIdentityVerifiedAt() != null
            && (now.isBefore(expiresAt(record)) || Boolean.TRUE.equals(record.getReturnArrivalReviewAccepted()));
    }
}
