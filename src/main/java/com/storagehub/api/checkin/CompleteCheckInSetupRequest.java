package com.storagehub.api.checkin;

import jakarta.validation.constraints.Size;

public record CompleteCheckInSetupRequest(
    @Size(max = 500) String note
) {
}
