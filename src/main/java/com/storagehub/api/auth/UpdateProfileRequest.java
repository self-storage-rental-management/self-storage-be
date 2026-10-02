package com.storagehub.api.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
    @NotBlank @Size(max = 160) String fullName,
    @Size(max = 30)
    @Pattern(
        regexp = "^$|^(?:\\+?[1-9]\\d{7,14}|0\\d{9,10})$",
        message = "phone must be a valid phone number"
    ) String phone,
    @Size(max = 500) String permanentAddress,
    @Size(max = 160) String emergencyContactName,
    @Size(max = 30) String emergencyContactPhone,
    @Size(max = 500_000) String avatarUrl
) {
}
