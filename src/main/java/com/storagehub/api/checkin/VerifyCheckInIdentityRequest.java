package com.storagehub.api.checkin;

import com.storagehub.validation.IdentityDocumentPolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyCheckInIdentityRequest(
    @NotBlank @Size(max = 30) String identityDocumentNumber,
    @Size(max = 1000) String note
) {
    @AssertTrue(message = IdentityDocumentPolicy.MESSAGE)
    public boolean isIdentityDocumentNumberValid() {
        return IdentityDocumentPolicy.isValid(identityDocumentNumber);
    }
}
