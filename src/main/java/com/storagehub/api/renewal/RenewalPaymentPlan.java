package com.storagehub.api.renewal;

public enum RenewalPaymentPlan {
    /** Historical JSON compatibility only; new monthly plans are rejected by the workflow. */
    @Deprecated MONTHLY,
    FULL_TERM;

    public static void requireSupported(RenewalPaymentPlan plan) {
        if (plan == MONTHLY) throw com.storagehub.common.api.ApiExceptions.conflict(
            "Hệ thống chưa áp dụng thanh toán gia hạn từng tháng. Vui lòng lấy báo giá trả trước 20%, phần còn lại thanh toán khi ký.");
    }
}
