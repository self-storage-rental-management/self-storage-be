package com.storagehub.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User extends BaseEntity {

    public User() {
        super();
    }

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(nullable = false, length = 100)
    @JsonIgnore
    private String passwordHash;

    @Column(nullable = false, length = 160)
    private String fullName;

    @Column(length = 30)
    private String phone;

    @Column(length = 500)
    private String permanentAddress;

    @Column(length = 160)
    private String emergencyContactName;

    @Column(length = 30)
    private String emergencyContactPhone;

    @JsonIgnore
    @Column(name = "identity_document_hash", length = 64, unique = true)
    private String identityDocumentHash;

    @jakarta.persistence.PrePersist
    @jakarta.persistence.PreUpdate
    private void updateIdentityHash() {
        String value = profileDetails == null ? null : com.storagehub.validation.CccdPolicy.normalize(profileDetails.getIdentityDocumentNumber());
        identityDocumentHash = null;
        if (value != null && !value.isBlank()) {
            profileDetails.setIdentityDocumentNumber(value);
            identityDocumentHash = com.storagehub.service.CustomerIdentityPolicy.hash(value);
        }
    }

    @jakarta.persistence.Embedded
    private CustomerProfileDetails profileDetails = new CustomerProfileDetails();

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean mustChangePassword = false;

    /**
     * Monotonic authorization version. Any role or facility-scope mutation
     * invalidates tokens carrying an older version.
     */
    @Column(name = "authz_version", nullable = false)
    private long authzVersion = 0L;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_roles",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();
}
