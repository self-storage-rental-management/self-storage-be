package com.storagehub.api.checkin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.storagehub.validation.ValidCccd;

public record VerifyCheckInIdentityRequest(
    @NotBlank @ValidCccd String identityDocumentNumber,
    @Size(max = 1000) String note,
    Boolean adulthoodConfirmed
) {
    public VerifyCheckInIdentityRequest(String identityDocumentNumber, String note) {
        this(identityDocumentNumber, note, null);
    }
}
