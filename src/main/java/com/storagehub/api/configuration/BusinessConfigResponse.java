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
    boolean autoProrate
) {}
