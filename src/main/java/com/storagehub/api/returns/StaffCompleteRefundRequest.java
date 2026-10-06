package com.storagehub.api.returns;

import jakarta.validation.constraints.Size;

public record StaffCompleteRefundRequest(
    @Size(max = 120, message = "transactionReference cannot exceed 120 characters")
    String transactionReference,

    @Size(max = 1000, message = "notes cannot exceed 1000 characters")
    String notes
) {}
