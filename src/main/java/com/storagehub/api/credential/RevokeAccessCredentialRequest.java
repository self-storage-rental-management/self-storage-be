package com.storagehub.api.credential;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RevokeAccessCredentialRequest(
    @NotBlank @Size(max = 1000) String reason
) {}
