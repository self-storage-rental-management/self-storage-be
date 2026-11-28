package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CustomerIdentityCorrectionTests {
    private static final String SAVED = "079200123456";

    @Test
    void permitsThreeChangesInSerialDigits() {
        assertThat(CustomerIdentityPolicy.correctionDistance(SAVED, "079200987456")).isEqualTo(3);
        assertThatCode(() -> CustomerIdentityPolicy.requireMinorCorrection(SAVED, "079200987456")).doesNotThrowAnyException();
    }

    @Test
    void rejectsFourSerialChanges() {
        assertThatThrownBy(() -> CustomerIdentityPolicy.requireMinorCorrection(SAVED, "079200987656"))
            .hasMessageContaining("tối đa 3");
    }

    @Test
    void rejectsEveryProtectedDigitEvenWhenOnlyOneDigitChanges() {
        for (int index = 0; index < 6; index++) {
            char[] corrected = SAVED.toCharArray();
            corrected[index] = corrected[index] == '9' ? '8' : (char) (corrected[index] + 1);
            String actual = new String(corrected);
            assertThat(CustomerIdentityPolicy.correctionDistance(SAVED, actual)).isEqualTo(Integer.MAX_VALUE);
            assertThatThrownBy(() -> CustomerIdentityPolicy.requireMinorCorrection(SAVED, actual))
                .hasMessageContaining("6 số đầu");
        }
    }
}
