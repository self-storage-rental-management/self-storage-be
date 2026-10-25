package com.storagehub.api.checkin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyCheckInIdentityRequest(
    @NotBlank @Size(max = 30) String identityDocumentNumber,
    @Size(max = 1000) String note
) {
}
