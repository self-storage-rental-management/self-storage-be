package com.storagehub.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Embeddable @Getter @Setter
public class CustomerProfileDetails {
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @Column(name = "profile_identity_verified_at")
    private java.time.Instant identityVerifiedAt;

    @com.storagehub.validation.ValidCccd
    @Column(name = "profile_identity_document_number", length = 12)
    private String identityDocumentNumber;
    @Size(max = 80)
    @Column(name = "profile_emergency_contact_relationship", length = 80)
    private String emergencyContactRelationship;
    @Email
    @Size(max = 320)
    @Column(name = "profile_emergency_contact_email", length = 320)
    private String emergencyContactEmail;
    @Column(name = "profile_emergency_contact_consent")
    private boolean emergencyContactConsent;
    @Size(max = 160)
    @Column(name = "profile_secondary_emergency_contact_name", length = 160)
    private String secondaryEmergencyContactName;
    @Size(max = 30)
    @Column(name = "profile_secondary_emergency_contact_phone", length = 30)
    private String secondaryEmergencyContactPhone;
    @Size(max = 80)
    @Column(name = "profile_secondary_emergency_contact_relationship", length = 80)
    private String secondaryEmergencyContactRelationship;
    @Email
    @Size(max = 320)
    @Column(name = "profile_secondary_emergency_contact_email", length = 320)
    private String secondaryEmergencyContactEmail;
    @Column(name = "profile_secondary_emergency_contact_consent")
    private boolean secondaryEmergencyContactConsent;
}
