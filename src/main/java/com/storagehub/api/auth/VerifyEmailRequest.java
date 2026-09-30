package com.storagehub.api.auth;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

public record VerifyEmailRequest(
    @Size(max = 320) String email,
    @NotBlank @Size(max = 128) String codeOrToken
) {
}
