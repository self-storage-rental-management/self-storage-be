package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Rental;
import com.storagehub.time.BusinessTime;
import java.time.Instant;
import java.time.LocalDate;

final class InventoryDeadlinePolicy {
    private InventoryDeadlinePolicy() {}

    static void requireInventoryOpen(Rental rental, Instant now) {
        var restriction = restriction(rental, now);
        if (restriction == null) return;
        switch (restriction) {
            case RECOVERY, REMOVAL_EXPIRED -> throw ApiExceptions.conflict(restriction.message);
            case VERIFICATION_LIMIT -> throw ApiExceptions.verificationLimit(restriction.message);
            case COMPLETION_EXPIRED, ARRIVAL_EXPIRED -> throw ApiExceptions.deadlineExpired(restriction.message);
        }
    }

    enum Restriction {
        RECOVERY("Hồ sơ đã chuyển sang thu hồi. Không thể tiếp tục kiểm kê."),
        REMOVAL_EXPIRED("Đã hết hạn kiểm kê. Vui lòng liên hệ Manager để xử lý."),
        VERIFICATION_LIMIT("Đã hết 2 lượt xác minh danh tính."),
        COMPLETION_EXPIRED("Đã hết hạn hoàn tất kiểm kê 72 giờ."),
        ARRIVAL_EXPIRED("Đã hết hạn kiểm kê. Vui lòng liên hệ Manager để xử lý.");
        final String message;
        Restriction(String message) { this.message = message; }
    }

    static Instant completionDeadline(Rental rental) {
        if (rental.getInspectionCompletionDeadlineAt() != null) return rental.getInspectionCompletionDeadlineAt();
        var started = rental.getInspectionClockStartedAt() != null ? rental.getInspectionClockStartedAt()
            : rental.getFirstIdentityVerificationAt();
        return started == null ? null : started.plus(java.time.Duration.ofHours(72));
    }

    static Restriction restriction(Rental rental, Instant now) {
        if (rental.getInventoryEscalatedAt() != null
            || expired(rental.getInventoryRemovalDeadlineAt(), now)) {
            return rental.getInventoryEscalatedAt() != null ? Restriction.RECOVERY : Restriction.REMOVAL_EXPIRED;
        }
        if (IntakeTimelineCalculator.VERIF_FAILED_MAX_ATTEMPTS.equals(rental.getVerificationStatus())) {
            return Restriction.VERIFICATION_LIMIT;
        }
        var completionDeadline = completionDeadline(rental);
        if (completionDeadline != null) {
            return now.isAfter(completionDeadline) ? Restriction.COMPLETION_EXPIRED : null;
        }
        if (IntakeTimelineCalculator.REVIEW_APPROVED_ON_TIME.equals(rental.getAttendanceReviewStatus())) {
            return null;
        }
        Instant arrivalRef = rental.getActualArrivedAt() != null
            ? rental.getActualArrivedAt()
            : rental.getInventoryArrivedAt();
        if (expired(rental.getInventoryFinalDeadlineAt(), now)
            && (arrivalRef == null || arrivalRef.isAfter(rental.getInventoryFinalDeadlineAt()))) {
            return Restriction.ARRIVAL_EXPIRED;
        }
        return null;
    }

    static void requireRemovalDate(Rental rental, LocalDate scheduledDate, Instant now) {
        Instant deadline = rental.getInventoryRemovalDeadlineAt();
        if (deadline == null) return; // Existing cases may predate publication tracking.
        if (expired(deadline, now)) {
            throw ApiExceptions.conflict("Đã hết hạn đưa hàng ra khỏi kho. Vui lòng liên hệ cơ sở.");
        }
        if (scheduledDate == null || !scheduledDate.atStartOfDay(BusinessTime.ZONE).toInstant().isBefore(deadline)) {
            throw ApiExceptions.validation("Ngày trả kho phải nằm trong thời hạn đưa hàng ra khỏi kho.", null);
        }
    }

    private static boolean expired(Instant deadline, Instant now) {
        return deadline != null && !now.isBefore(deadline);
    }
}
