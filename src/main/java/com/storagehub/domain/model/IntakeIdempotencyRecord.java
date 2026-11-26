package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
    name = "intake_idempotency_records",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_intake_idem",
        columnNames = {"rental_id", "action", "idempotency_key"}
    )
)
@Getter
@Setter
public class IntakeIdempotencyRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rental_id", nullable = false)
    private Rental rental;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(columnDefinition = "LONGTEXT")
    private String responseJson;
}
