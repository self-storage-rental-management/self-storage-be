package com.storagehub.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RegisterRequestValidationTests {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @MethodSource("validPhones")
    void acceptsVietnameseMobileNumbersForBothContacts(String phone) {
        assertThat(phoneViolations(phone, phone)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidPhones")
    void rejectsInvalidPhonesForBothContacts(String phone) {
        assertThat(phoneViolations(phone, phone)).containsExactlyInAnyOrder("phone", "emergencyContactPhone");
    }

    private Set<String> phoneViolations(String phone, String emergencyPhone) {
        RegisterRequest request = new RegisterRequest(
            "customer@example.com", "Valid@1234", "Nguyễn Văn An", phone,
            "123 Nguyễn Huệ, Quận 1", "Nguyễn Văn Bình", emergencyPhone
        );
        return VALIDATOR.validate(request).stream()
            .map(violation -> violation.getPropertyPath().toString())
            .filter(path -> path.equals("phone") || path.equals("emergencyContactPhone"))
            .collect(java.util.stream.Collectors.toSet());
    }

    private static Stream<String> validPhones() {
        return Stream.of("0321234567", "0561234567", "0781234567", "0812345678", "0871234567", "0908123456", "0991234567");
    }

    private static Stream<String> invalidPhones() {
        return Stream.of(
            "", "0298123456", "0311234567", "0511234567", "0751234567", "0801234567", "0951234567",
            "090812345", "09081234567", "+84908123456",
            "84908123456", "090 812 3456", "090-812-3456", "090.812.3456", "0908A23456"
        );
    }
}
