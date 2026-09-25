package com.storagehub.api.payment;

import com.storagehub.domain.model.PaymentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record CreatePaymentIntentRequest(
    UUID reservationId,
    @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
    @NotBlank @Size(min = 3, max = 3) String currency,
    @NotNull PaymentType purpose
) {
}
