package com.storagehub.api.returns;

import com.storagehub.domain.model.StorageUnitStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record ManagerReviewDisputeRequest(
    @NotBlank(message = "resolutionNote is required")
    @Size(max = 2000, message = "resolutionNote cannot exceed 2000 characters")
    String resolutionNote,

    @NotNull(message = "damageFee is required")
    @DecimalMin(value = "0.00", message = "damageFee must be >= 0")
    BigDecimal damageFee,

    @NotNull(message = "cleaningFee is required")
    @DecimalMin(value = "0.00", message = "cleaningFee must be >= 0")
    BigDecimal cleaningFee,

    @NotNull(message = "lostItemFee is required")
    @DecimalMin(value = "0.00", message = "lostItemFee must be >= 0")
    BigDecimal lostItemFee,

    @NotNull(message = "overdueFee is required")
    @DecimalMin(value = "0.00", message = "overdueFee must be >= 0")
    BigDecimal overdueFee,

    @NotNull(message = "outstandingFee is required")
    @DecimalMin(value = "0.00", message = "outstandingFee must be >= 0")
    BigDecimal outstandingFee,

    StorageUnitStatus proposedUnitStatus
) {}
