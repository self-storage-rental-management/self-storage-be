package com.storagehub.service;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CustomerProfileDetailsPolicyTests {
    @Test void savesIdentityAndContactWithoutLosingOtherDetails() {
        var result = CustomerProfileDetailsPolicy.merge(Map.of("emergencyContactEmail", "contact@example.com"),
            Map.of("identityDocumentNumber", "079203009988", "emergencyContactRelationship", "Cha/mẹ", "emergencyContactConsent", true));
        assertThat(result).containsEntry("identityDocumentNumber", "079203009988")
            .containsEntry("emergencyContactRelationship", "Cha/mẹ")
            .containsEntry("emergencyContactEmail", "contact@example.com").containsEntry("emergencyContactConsent", true);
    }
    @Test void verificationCannotBeForgedOrCleared() {
        assertThat(CustomerProfileDetailsPolicy.merge(null, Map.of("identityVerifiedAt", "fake"))).doesNotContainKey("identityVerifiedAt");
        assertThatThrownBy(() -> CustomerProfileDetailsPolicy.merge(
            Map.of("identityVerifiedAt", "verified", "identityDocumentNumber", "079203009988"),
            Map.of("identityDocumentNumber", "079203009999"))).isInstanceOf(RuntimeException.class);
    }
    @Test void rejectsMalformedIdentity() {
        assertThatThrownBy(() -> CustomerProfileDetailsPolicy.merge(null, Map.of("identityDocumentNumber", "abc"))).isInstanceOf(RuntimeException.class);
    }
}
