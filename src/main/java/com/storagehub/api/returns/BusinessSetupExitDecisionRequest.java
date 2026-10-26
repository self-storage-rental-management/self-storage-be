package com.storagehub.api.returns;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record BusinessSetupExitDecisionRequest(
    @NotNull(message = "approvedRefundAmount is required")
    @DecimalMin(value = "0.00", message = "approvedRefundAmount must be >= 0")
    @Digits(integer = 12, fraction = 2, message = "approvedRefundAmount is invalid")
    BigDecimal approvedRefundAmount,

    @NotBlank(message = "resolutionNote is required")
    @Size(max = 2000, message = "resolutionNote cannot exceed 2000 characters")
    String resolutionNote,

    @DecimalMin(value = "0.00", message = "approvedRentalRefundAmount must be >= 0")
    @Digits(integer = 12, fraction = 2, message = "approvedRentalRefundAmount is invalid")
    BigDecimal approvedRentalRefundAmount
) {}
