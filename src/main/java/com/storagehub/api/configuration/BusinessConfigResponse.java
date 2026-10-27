package com.storagehub.api.configuration;

import java.math.BigDecimal;

public record BusinessConfigResponse(
    int gracePeriodDays,
    BigDecimal lateFeeAmount,
    BigDecimal defaultDepositRatio,
    double holdExpiryHours,
    int dimDivisor,
    boolean maintenanceMode,
    String bannerNotice,
    int autoInvoiceDays,
    boolean autoProrate,
    BigDecimal managerRefundApprovalLimit
) {
    public BusinessConfigResponse(int gracePeriodDays, BigDecimal lateFeeAmount, BigDecimal defaultDepositRatio,
        double holdExpiryHours, int dimDivisor, boolean maintenanceMode, String bannerNotice,
        int autoInvoiceDays, boolean autoProrate) {
        this(gracePeriodDays, lateFeeAmount, defaultDepositRatio, holdExpiryHours, dimDivisor,
            maintenanceMode, bannerNotice, autoInvoiceDays, autoProrate, new BigDecimal("5000000"));
    }
}
