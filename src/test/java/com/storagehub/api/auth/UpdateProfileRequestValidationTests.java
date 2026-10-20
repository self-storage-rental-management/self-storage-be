package com.storagehub.api.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ConstraintViolation;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UpdateProfileRequestValidationTests {
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void allowsBothEmergencyContactFieldsToBeEmpty() {
        assertTrue(validate(null, null).isEmpty());
    }

    @Test
    void rejectsEmergencyContactNameWithoutPhone() {
        assertTrue(validate("Nguyen Van B", null).stream()
            .anyMatch(violation -> violation.getMessage().contains("provided together")));
    }

    @Test
    void rejectsEmergencyContactPhoneWithoutName() {
        assertTrue(validate(null, "0901234567").stream()
            .anyMatch(violation -> violation.getMessage().contains("provided together")));
    }

    private Set<ConstraintViolation<UpdateProfileRequest>> validate(String emergencyName, String emergencyPhone) {
        return VALIDATOR.validate(new UpdateProfileRequest(
            "Nguyen Van A",
            "0901234567",
            "123 Nguyen Hue",
            emergencyName,
            emergencyPhone,
            null
        ));
    }
}
