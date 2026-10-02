package com.storagehub.api.configuration;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record UpdateBusinessConfigRequest(
    @Min(value = 0, message = "gracePeriodDays must be >= 0")
    Integer gracePeriodDays,

    @DecimalMin(value = "0.0", message = "lateFeeAmount must be >= 0")
    BigDecimal lateFeeAmount,

    @DecimalMin(value = "0.0", message = "defaultDepositRatio must be >= 0")
    BigDecimal defaultDepositRatio,

    Double holdExpiryHours,

    @Min(value = 1, message = "dimDivisor must be >= 1")
    Integer dimDivisor,

    Boolean maintenanceMode,

    @Size(max = 500, message = "bannerNotice cannot exceed 500 characters")
    String bannerNotice,

    @Min(value = 1, message = "autoInvoiceDays must be >= 1")
    Integer autoInvoiceDays,

    Boolean autoProrate
) {}
