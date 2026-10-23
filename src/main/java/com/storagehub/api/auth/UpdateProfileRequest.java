package com.storagehub.api.auth;

import com.storagehub.validation.PhoneNumberPolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
    @NotBlank @Size(min = 2, max = 160) String fullName,
    @Size(max = 30) String phone,
    @Size(max = 500)
    @Pattern(regexp = "^$|^.{5,500}$", message = "permanentAddress must be empty or at least 5 characters")
    String permanentAddress,
    @Size(max = 160)
    @Pattern(regexp = "^$|^.{2,160}$", message = "emergencyContactName must be empty or at least 2 characters")
    String emergencyContactName,
    @Size(max = 30) String emergencyContactPhone,
    @Size(max = 500_000) String avatarUrl
) {
    @AssertTrue(message = "emergency contact name and phone must be provided together")
    public boolean isEmergencyContactComplete() {
        boolean hasName = emergencyContactName != null && !emergencyContactName.isBlank();
        boolean hasPhone = emergencyContactPhone != null && !emergencyContactPhone.isBlank();
        return hasName == hasPhone;
    }

    @AssertTrue(message = PhoneNumberPolicy.MESSAGE)
    public boolean isPhoneNumbersValid() {
        return PhoneNumberPolicy.isOptionalValid(phone)
            && PhoneNumberPolicy.isOptionalValid(emergencyContactPhone);
    }
}
