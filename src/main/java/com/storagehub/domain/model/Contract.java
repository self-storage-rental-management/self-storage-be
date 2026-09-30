package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "contracts")
@Getter
@Setter
@NoArgsConstructor
public class Contract extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rental_id", nullable = false)
    private Rental rental;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ContractStatus status = ContractStatus.DRAFT;

    @Column(nullable = false, unique = true, length = 64)
    private String contractNumber;

    @Column
    private Instant sentAt;

    @Column
    private Instant signedAt;

    @Column
    private Instant expiresAt;

    @Column(length = 64)
    private String signedBy;
}
