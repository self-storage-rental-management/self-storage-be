package com.storagehub.api.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
    @NotBlank @Size(min = 2, max = 160) String fullName,
    @Size(max = 30)
    @Pattern(
        regexp = "^$|^(?:\\+?[1-9]\\d{7,14}|0\\d{9,10})$",
        message = "phone must be a valid phone number"
    ) String phone,
    @Size(max = 500)
    @Pattern(regexp = "^$|^.{5,500}$", message = "permanentAddress must be empty or at least 5 characters")
    String permanentAddress,
    @Size(max = 160)
    @Pattern(regexp = "^$|^.{2,160}$", message = "emergencyContactName must be empty or at least 2 characters")
    String emergencyContactName,
    @Size(max = 30)
    @Pattern(
        regexp = "^$|^(?:\\+?[1-9]\\d{7,14}|0\\d{9,10})$",
        message = "emergencyContactPhone must be a valid phone number"
    ) String emergencyContactPhone,
    @Size(max = 500_000) String avatarUrl
) {
}
