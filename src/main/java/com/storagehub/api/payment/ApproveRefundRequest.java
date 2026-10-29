package com.storagehub.api.payment;

import jakarta.validation.constraints.Size;

public record ApproveRefundRequest(
    @Size(max = 500, message = "resolutionNote cannot exceed 500 characters")
    String resolutionNote
) {}
