package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.ReturnCase;
import java.math.BigDecimal;

/** Terms are captured in the accepted initial quote, never looked up from the current catalog. */
public final class SetupEarlyExitPolicy {
    public static final String VERSION = "SETUP_EARLY_EXIT_ONE_MONTH_V1";
    private SetupEarlyExitPolicy() {}

    public static boolean applies(ReturnCase rc) {
        var rental = rc.getRental();
        return rc.isSetupExitRequested() && rc.getSetupReturnedAt() != null
            && rental.getSetupStartedAt() != null && rental.getSetupEndsAt() != null
            && !rc.getSetupReturnedAt().isBefore(rental.getSetupStartedAt())
            && rc.getSetupReturnedAt().isBefore(rental.getSetupEndsAt());
    }

    public static void capture(ReturnCase rc, ReservationPricingSnapshot pricing) {
        if (rc.isSetupExitRequested() && (rc.getSetupReturnedAt() == null
            || rc.getRental().getSetupStartedAt() == null || rc.getRental().getSetupEndsAt() == null))
            throw ApiExceptions.conflict("Thiếu chứng cứ thời điểm bàn giao hoặc trả kho trong thời gian chuẩn bị. Cần đối soát trước khi quyết toán.");
        boolean applies = applies(rc);
        if (rc.getReturnType() == com.storagehub.domain.model.ReturnType.INVENTORY_MISMATCH) {
            if (pricing == null || pricing.getNetRentalAmount() == null || pricing.getMonthlyPrice() == null
                || pricing.getNetRentalAmount().signum() < 0 || pricing.getMonthlyPrice().signum() < 0)
                throw ApiExceptions.conflict("Thiếu bảng giá đã chốt để quyết toán kiểm kê sai lệch.");
            rc.setSetupEarlyExit(false);
            rc.setRentalRefundCredit(pricing.getNetRentalAmount());
            rc.setEarlyTerminationFee(BigDecimal.ZERO);
            rc.setSetupEarlyExitPolicyVersion("INVENTORY_MISMATCH_USAGE_V1");
            return;
        }
        rc.setSetupEarlyExit(applies);
        rc.setRentalRefundCredit(BigDecimal.ZERO);
        rc.setEarlyTerminationFee(BigDecimal.ZERO);
        rc.setSetupEarlyExitPolicyVersion(null);
        if (!applies) return;
        var quote = rc.getRental().getReservation().getSourceQuote();
        if (quote == null || !VERSION.equals(pricing.getSetupEarlyExitPolicyVersion())
            || !VERSION.equals(quote.getSetupEarlyExitPolicyVersion())
            || pricing.getSetupEarlyExitFee() == null || pricing.getMonthlyPrice() == null
            || quote.getSetupEarlyExitFee() == null || pricing.getNetRentalAmount() == null
            || pricing.getSetupEarlyExitFee().signum() < 0
            || pricing.getSetupEarlyExitFee().compareTo(pricing.getMonthlyPrice()) != 0
            || pricing.getSetupEarlyExitFee().compareTo(quote.getSetupEarlyExitFee()) != 0) {
            throw ApiExceptions.conflict("Đơn chưa có điều khoản trả kho trong thời gian chuẩn bị đã chấp nhận. Cần đối soát thủ công, không tự áp phí mới.");
        }
        rc.setSetupEarlyExitPolicyVersion(pricing.getSetupEarlyExitPolicyVersion());
        rc.setRentalRefundCredit(pricing.getNetRentalAmount());
        rc.setEarlyTerminationFee(pricing.getSetupEarlyExitFee());
    }
}
