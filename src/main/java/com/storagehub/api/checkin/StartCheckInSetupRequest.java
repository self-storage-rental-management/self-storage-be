package com.storagehub.api.checkin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StartCheckInSetupRequest(
    @NotBlank @Size(max = 160) String lockReference,
    @Size(max = 500) String note
) {
}
