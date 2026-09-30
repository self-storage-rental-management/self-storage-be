package com.storagehub.api.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
    @Email @Size(max = 320) String email,
    @NotBlank @Size(max = 128) String codeOrToken,
    @NotBlank @Size(min = 12, max = 128) String newPassword
) {
}
