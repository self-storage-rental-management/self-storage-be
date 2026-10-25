package com.storagehub.api.checkin;

import jakarta.validation.constraints.Size;

public record ConfirmCheckInPaymentRequest(
    @Size(max = 500) String note
) {
}
