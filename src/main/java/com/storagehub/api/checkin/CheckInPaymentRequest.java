package com.storagehub.api.checkin;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record CheckInPaymentRequest(
    @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
    @NotBlank @Size(max = 32) String paymentMethod,
    @NotNull UUID paymentEvidenceFileId,
    @Size(max = 40) String transactionReference,
    @Size(max = 500) String note
) {
}
