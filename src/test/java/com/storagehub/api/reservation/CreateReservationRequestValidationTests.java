package com.storagehub.api.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CreateReservationRequestValidationTests {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @MethodSource("validVietnameseMobileNumbers")
    void acceptsVietnameseContactNumbers(String phone) {
        assertThat(phoneViolations(phone)).isZero();
    }

    @ParameterizedTest
    @MethodSource("invalidVietnameseMobileNumbers")
    void rejectsNonVietnameseOrMalformedContactNumbers(String phone) {
        assertThat(phoneViolations(phone)).isPositive();
    }

    @Test
    void rejectsBlankPhone() {
        assertThat(phoneViolations(" ")).isPositive();
    }

    private long phoneViolations(String phone) {
        CreateReservationRequest request = new CreateReservationRequest();
        request.setCustomerPhone(phone);
        return VALIDATOR.validate(request).stream().filter(violation ->
            java.util.Set.of("customerPhone", "customerPhoneValid").contains(violation.getPropertyPath().toString())).count();
    }

    private static Stream<String> validVietnameseMobileNumbers() {
        return Stream.of(
            "0908123456",
            "0381234567",
            "0561234567",
            "0781234567",
            "0812345678",
            "0871234567",
            "0991234567",
            "0298123456", "0311234567", "0511234567", "0751234567", "0801234567", "0951234567",
            "84908123456", "+84908123456", "038 123 4567", "056-123-4567", "090.812.3456", "090(812)3456"
        );
    }

    private static Stream<String> invalidVietnameseMobileNumbers() {
        return Stream.of(
            "1234567890",
            "0411234567",
            "0611234567",
            "090812345",
            "09081234567",
            "+8490812345",
            "+12025550123",
            "0908A23456"
        );
    }
}
