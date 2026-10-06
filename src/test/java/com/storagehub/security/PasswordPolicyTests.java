package com.storagehub.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTests {

    @Test
    void acceptsAComplexPasswordWithEightCharacters() {
        assertThat(PasswordPolicy.isValid("Aa1!aaaa")).isTrue();
    }

    @Test
    void rejectsPasswordsMissingARequiredCharacterClass() {
        assertThat(PasswordPolicy.isValid("aa1!aaaa")).isFalse();
        assertThat(PasswordPolicy.isValid("AA1!AAAA")).isFalse();
        assertThat(PasswordPolicy.isValid("Aa!aaaaa")).isFalse();
        assertThat(PasswordPolicy.isValid("Aa1aaaaa")).isFalse();
    }

    @Test
    void rejectsWhitespaceAndInvalidLengths() {
        assertThat(PasswordPolicy.isValid("Aa1! aa a")).isFalse();
        assertThat(PasswordPolicy.isValid("Aa1!aaa")).isFalse();
        assertThat(PasswordPolicy.isValid("A".repeat(126) + "a1!")).isFalse();
    }
}