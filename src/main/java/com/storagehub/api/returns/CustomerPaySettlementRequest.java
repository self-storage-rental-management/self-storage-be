package com.storagehub.api.returns;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomerPaySettlementRequest(
    @NotBlank(message = "paymentMethod is required")
    @Size(max = 40)
    String paymentMethod,

    @Size(max = 1000)
    String notes
) {}
