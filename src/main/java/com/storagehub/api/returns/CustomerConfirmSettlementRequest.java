package com.storagehub.api.returns;

import com.storagehub.domain.model.CustomerDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CustomerConfirmSettlementRequest(
    @NotNull(message = "decision is required")
    CustomerDecision decision,

    @Size(max = 2000, message = "note cannot exceed 2000 characters")
    String note
) {}
