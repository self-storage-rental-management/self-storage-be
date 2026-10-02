package com.storagehub.api.payment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PaymentComplaintDecisionRequest(
    @NotNull Decision decision,
    @Size(max = 2000) String reason
) {
    public enum Decision { APPROVE, REJECT }
}
