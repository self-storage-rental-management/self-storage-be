package com.storagehub.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhoneNumberPolicyTests {

    @Test
    void acceptsVietnameseMobileAndInternationalForms() {
        assertThat(PhoneNumberPolicy.isValid("0908123456")).isTrue();
        assertThat(PhoneNumberPolicy.isValid("+84 908 123 456")).isTrue();
        assertThat(PhoneNumberPolicy.isValid("84-908-123-456")).isTrue();
    }

    @Test
    void acceptsVietnameseLandlineForms() {
        assertThat(PhoneNumberPolicy.isValid("0241234567")).isTrue();
        assertThat(PhoneNumberPolicy.isValid("+84241234567")).isTrue();
    }

    @Test
    void rejectsUnsupportedPrefixesAndLengths() {
        assertThat(PhoneNumberPolicy.isValid("0108123456")).isFalse();
        assertThat(PhoneNumberPolicy.isValid("090812345")).isFalse();
        assertThat(PhoneNumberPolicy.isValid("+8490812345")).isFalse();
    }

    @Test
    void allowsBlankOptionalPhone() {
        assertThat(PhoneNumberPolicy.isOptionalValid(null)).isTrue();
        assertThat(PhoneNumberPolicy.isOptionalValid(" ")).isTrue();
    }
}
