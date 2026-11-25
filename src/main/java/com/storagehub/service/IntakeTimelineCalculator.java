package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Centralized calculation and transition guard service for unified Setup & Inspection deadlines:
 * T0 (receivedAt) -> D0 -> D (current arrival deadline) -> C (setup cutoff) -> R (review) -> V -> F (72h completion).
 */
@Service
public class IntakeTimelineCalculator {

    public static final String APPT_NONE = "NONE";
    public static final String APPT_ACTIVE = "ACTIVE";
    public static final String APPT_NOT_READY = "NOT_READY";
    public static final String APPT_MISSED = "MISSED";
    public static final String APPT_ATTENDED = "ATTENDED";
    public static final String APPT_CANCELLED = "CANCELLED";

    public static final String REVIEW_NONE = "NONE";
    public static final String REVIEW_PENDING = "PENDING";
    public static final String REVIEW_APPROVED_ON_TIME = "APPROVED_ON_TIME";
    public static final String REVIEW_CONFIRMED_ABSENT = "CONFIRMED_ABSENT";
    public static final String REVIEW_FACILITY_EXCEPTION = "FACILITY_EXCEPTION";

    public static final String VERIF_NOT_STARTED = "NOT_STARTED";
    public static final String VERIF_IN_PROGRESS = "IN_PROGRESS";
    public static final String VERIF_PASSED = "PASSED";
    public static final String VERIF_FAILED_MAX_ATTEMPTS = "FAILED_MAX_ATTEMPTS";

    private final IntakePolicyProperties policy;

    public IntakeTimelineCalculator(IntakePolicyProperties policy) {
        this.policy = policy;
    }

    public boolean isUnifiedPolicy(Rental rental) {
        return rental != null && IntakePolicyProperties.POLICY_VERSION.equals(rental.getIntakePolicyVersion());
    }

    /**
     * Initializes T0, D0, D, extensionCount=0, and immutable policy snapshot on valid receipt confirmation.
     * Repeated calls on an already-initialized rental never reset T0/D0/D/count.
     */
    public void initializeOnReceipt(Rental rental, Instant receivedAt) {
        if (rental == null || receivedAt == null) return;
        if (rental.getOriginalInspectionArrivalDeadlineAt() != null
            && IntakePolicyProperties.POLICY_VERSION.equals(rental.getIntakePolicyVersion())) {
            return;
        }
        String code = rental.getStorageUnit() == null || rental.getStorageUnit().getUnitType() == null
            ? null : rental.getStorageUnit().getUnitType().getCode();
        IntakePolicyProperties.SizeRule rule = policy.ruleFor(code);
        if (rule == null) {
            throw ApiExceptions.conflict("Cỡ kho chưa được cấu hình thời hạn setup và kiểm kê.");
        }
        Duration initialDuration = Duration.ofHours(rule.getInitialArrivalHours());
        Instant d0 = receivedAt.plus(initialDuration);

        rental.setIntakePolicyVersion(IntakePolicyProperties.POLICY_VERSION);
        rental.setIntakeInitialDurationHours(rule.getInitialArrivalHours());
        rental.setIntakeExtensionMaxHours(rule.getMaxExtensionHours());
        rental.setIntakeExtensionMaxCount(policy.getMaxExtensions());
        rental.setIntakeCompletionHours(policy.getCompletionWindowHours());

        rental.setOriginalInspectionArrivalDeadlineAt(d0);
        rental.setInventoryFinalDeadlineAt(d0);
        rental.setSetupCompletionDeadlineAt(d0);
        rental.setSetupExtensionDays(rule.getMaxExtensionHours() / 24);
        rental.setSetupExtensionCount(0);

        if (rental.getAppointmentStatus() == null) {
            rental.setAppointmentStatus(APPT_NONE);
        }
        if (rental.getAttendanceReviewStatus() == null) {
            rental.setAttendanceReviewStatus(REVIEW_NONE);
        }
        if (rental.getVerificationStatus() == null) {
            rental.setVerificationStatus(VERIF_NOT_STARTED);
        }
    }

    /** Current inspection arrival deadline D (unified or legacy fallback). */
    public Instant arrivalDeadline(Rental rental) {
        if (rental == null) return null;
        if (rental.getInventoryFinalDeadlineAt() != null) return rental.getInventoryFinalDeadlineAt();
        return rental.getSetupCompletionDeadlineAt();
    }

    /** Active appointment A (null when no active appointment or appointment was invalidated/missed/cancelled). */
    public Instant activeAppointment(Rental rental) {
        if (rental == null || rental.getInventoryAppointmentAt() == null) return null;
        String status = rental.getAppointmentStatus();
        if (status == null || APPT_ACTIVE.equals(status)) {
            return rental.getInventoryAppointmentAt();
        }
        return null;
    }

    /** Setup cutoff C = (A != null ? A : D) - setupCutoffHours (6h). */
    public Instant setupCutoff(Rental rental) {
        Instant d = arrivalDeadline(rental);
        if (d == null) return null;
        Instant a = activeAppointment(rental);
        Instant anchor = a != null ? a : d;
        return anchor.minus(Duration.ofHours(policy.getSetupCutoffHours()));
    }

    /** Reconciliation deadline R = D + attendanceReviewMinutes (P01). */
    public Instant reconciliationDeadline(Instant arrivalDeadline) {
        if (arrivalDeadline == null || policy.getAttendanceReviewMinutes() <= 0) return null;
        return arrivalDeadline.plus(Duration.ofMinutes(policy.getAttendanceReviewMinutes()));
    }

    /** Completion deadline F = V + completionWindowHours (72h). */
    public Instant completionDeadline(Instant firstVerificationAt) {
        if (firstVerificationAt == null) return null;
        return firstVerificationAt.plus(Duration.ofHours(policy.getCompletionWindowHours()));
    }

    /** Setup completion allowed iff receivedAt <= now <= C. */
    public boolean canDeclareSetupComplete(Rental rental, Instant now) {
        if (rental == null || now == null) return false;
        Instant receivedAt = rental.getSetupStartedAt();
        Instant cutoff = setupCutoff(rental);
        if (receivedAt == null || cutoff == null) return false;
        return !now.isBefore(receivedAt) && !now.isAfter(cutoff);
    }

    /** Appointment validation according to Section 2 & B06-B20. */
    public void validateAppointment(Rental rental, Instant now, Instant requestedAt) {
        if (rental == null || now == null || requestedAt == null) {
            throw ApiExceptions.validation("Vui lòng chọn ngày giờ hẹn kiểm kê.", null);
        }
        if (isTerminalOrRecovery(rental) || rental.getFirstIdentityVerificationAt() != null
            || rental.getInventoryArrivedAt() != null || rental.getActualArrivedAt() != null) {
            throw ApiExceptions.invalidState("Hồ sơ đã tiếp nhận hoặc chuyển luồng xử lý, không thể đặt lịch.");
        }
        Instant d = arrivalDeadline(rental);
        if (d == null) {
            throw ApiExceptions.invalidState("Hồ sơ chưa khởi tạo hạn đến kiểm kê.");
        }
        if (requestedAt.isBefore(now)) {
            throw ApiExceptions.validation("Không thể chọn lịch hẹn trong quá khứ.", null);
        }
        if (requestedAt.isAfter(d)) {
            throw ApiExceptions.deadlineExpired("Lịch hẹn không được vượt quá hạn đến kiểm kê.");
        }
        Duration lead = Duration.ofHours(policy.getSetupCutoffHours());
        Instant setupCompletedAt = rental.getMoveInCompletedAt();
        Instant minAppointment = setupCompletedAt != null
            ? setupCompletedAt.plus(lead)
            : now.plus(lead);
        if (requestedAt.isBefore(minAppointment)) {
            throw ApiExceptions.setupCutoffExceeded(
                "Lịch hẹn phải cách thời điểm " + (setupCompletedAt != null ? "hoàn tất setup" : "hiện tại")
                    + " ít nhất " + policy.getSetupCutoffHours() + " giờ.");
        }
    }

    /** Unified extension validation according to Section 2 & 3.3 & C01-C21. */
    public void validateExtension(Rental rental, Instant now, Instant requestedDeadline) {
        if (rental == null || now == null || requestedDeadline == null) {
            throw ApiExceptions.validation("Vui lòng chọn thời hạn gia hạn hợp lệ.", null);
        }
        if (rental.getSetupStartedAt() == null) {
            throw ApiExceptions.invalidState("Khách hàng chưa xác nhận nhận kho.");
        }
        if (isTerminalOrRecovery(rental) || REVIEW_PENDING.equals(rental.getAttendanceReviewStatus())
            || rental.getFirstIdentityVerificationAt() != null
            || rental.getInventoryArrivedAt() != null || rental.getActualArrivedAt() != null) {
            throw ApiExceptions.invalidState("Hồ sơ hiện không thể gia hạn thông thường.");
        }
        Instant d = arrivalDeadline(rental);
        if (d == null || !now.isBefore(d)) {
            throw ApiExceptions.deadlineExpired("Đã hết hạn đến kiểm kê, không thể gia hạn thêm.");
        }
        int maxCount = rental.getIntakeExtensionMaxCount() != null
            ? rental.getIntakeExtensionMaxCount() : policy.getMaxExtensions();
        int usedCount = rental.getSetupExtensionCount() != null ? rental.getSetupExtensionCount() : 0;
        if (usedCount >= maxCount) {
            throw ApiExceptions.extensionLimit("Bạn đã sử dụng hết " + maxCount + " lần gia hạn cho phép.");
        }
        int maxHours = maxExtensionHoursFor(rental);
        if (maxHours <= 0) {
            throw ApiExceptions.conflict("Cỡ kho này chưa được cấu hình mức gia hạn.");
        }
        if (!requestedDeadline.isAfter(d)) {
            throw ApiExceptions.validation("Thời hạn gia hạn phải lớn hơn hạn hiện tại.", null);
        }
        Duration delta = Duration.between(d, requestedDeadline);
        Duration maxDelta = Duration.ofHours(maxHours);
        if (delta.compareTo(maxDelta) > 0) {
            throw ApiExceptions.validation("Mỗi lần gia hạn không được vượt quá " + maxHours + " giờ.", null);
        }
        if (policy.isRequireWholeDayExtensions()) {
            long seconds = delta.getSeconds();
            if (seconds <= 0 || seconds % 86_400L != 0 || delta.getNano() != 0) {
                throw ApiExceptions.validation("Số ngày gia hạn phải là số ngày nguyên dương (bội số 24 giờ).", null);
            }
        }
    }

    public int maxExtensionHoursFor(Rental rental) {
        if (rental.getIntakeExtensionMaxHours() != null) return rental.getIntakeExtensionMaxHours();
        if (rental.getSetupExtensionDays() != null) return rental.getSetupExtensionDays() * 24;
        String code = rental.getStorageUnit() == null || rental.getStorageUnit().getUnitType() == null
            ? null : rental.getStorageUnit().getUnitType().getCode();
        IntakePolicyProperties.SizeRule rule = policy.ruleFor(code);
        return rule == null ? 0 : rule.getMaxExtensionHours();
    }

    public boolean isTerminalOrRecovery(Rental rental) {
        if (rental == null) return true;
        if (rental.getCompletedAt() != null || rental.getActualReturnedAt() != null
            || rental.getInventoryEscalatedAt() != null || rental.getInventoryRemovalPublishedAt() != null) {
            return true;
        }
        return !List.of(RentalStatus.setup, RentalStatus.active).contains(rental.getStatus());
    }

    /** Arrived on time iff receivedAt <= actualArrivedAt <= D (inclusive). */
    public boolean isArrivalOnTime(Rental rental, Instant actualArrivedAt) {
        Instant d = arrivalDeadline(rental);
        Instant t0 = rental.getSetupStartedAt();
        if (d == null || t0 == null || actualArrivedAt == null) return false;
        return !actualArrivedAt.isBefore(t0) && !actualArrivedAt.isAfter(d);
    }

    /** Inspection completion valid iff now <= F (inclusive). */
    public boolean isWithinCompletionDeadline(Rental rental, Instant now) {
        Instant f = rental == null ? null : rental.getInspectionCompletionDeadlineAt();
        if (f == null || now == null) return false;
        return !now.isAfter(f);
    }
}
