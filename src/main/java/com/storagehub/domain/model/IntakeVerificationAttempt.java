package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
    name = "intake_verification_attempts",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_intake_verif_ordinal",
        columnNames = {"rental_id", "substantive_ordinal"}
    )
)
@Getter
@Setter
public class IntakeVerificationAttempt extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rental_id", nullable = false)
    private Rental rental;

    /** 1 or 2 for substantive attempts (PASS/FAIL); null for TECH_ERROR. */
    @Column(name = "substantive_ordinal")
    private Integer substantiveOrdinal;

    /** PASS, FAIL, or TECH_ERROR. */
    @Column(nullable = false, length = 32)
    private String outcome;

    @Column(nullable = false)
    private Instant recordedAt;

    @Column(nullable = false)
    private UUID staffId;

    @Column(length = 64)
    private String documentNumberMasked;

    @Column
    private UUID evidenceFileId;

    @Column(length = 500)
    private String reason;

    @Column(length = 128)
    private String idempotencyKey;
}
