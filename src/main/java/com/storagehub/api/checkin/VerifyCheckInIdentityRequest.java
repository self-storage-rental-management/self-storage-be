package com.storagehub.api.checkin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.storagehub.validation.ValidCccd;

public record VerifyCheckInIdentityRequest(
    @NotBlank @ValidCccd String identityDocumentNumber,
    @Size(max = 1000) String note,
    Boolean adulthoodConfirmed,
    Boolean bookingOwnerConfirmed,
    Boolean correctionConfirmed
) {
    public VerifyCheckInIdentityRequest(String identityDocumentNumber, String note, Boolean adulthoodConfirmed, Boolean bookingOwnerConfirmed) {
        this(identityDocumentNumber, note, adulthoodConfirmed, bookingOwnerConfirmed, null);
    }

    public VerifyCheckInIdentityRequest(String identityDocumentNumber, String note, Boolean adulthoodConfirmed) {
        this(identityDocumentNumber, note, adulthoodConfirmed, null);
    }

    public VerifyCheckInIdentityRequest(String identityDocumentNumber, String note) {
        this(identityDocumentNumber, note, null);
    }
}
