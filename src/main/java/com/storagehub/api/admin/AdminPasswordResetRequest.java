package com.storagehub.api.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminPasswordResetRequest(
    @NotBlank @Size(min = 12, max = 128) String temporaryPassword
) {
}
