package com.storagehub.api.checkin;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record VerifyInventoryIdentityRequest(
    @NotBlank @Size(max = 30) String identityDocumentNumber,
    @NotNull UUID photoId,
    @NotNull @AssertTrue Boolean bookingCustomerPresent
) {}
