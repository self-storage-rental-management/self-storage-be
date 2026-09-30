package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "storage_units")
@Getter
@Setter
@NoArgsConstructor
public class StorageUnit extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facility_id", nullable = false)
    private Facility facility;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_type_id", nullable = false)
    private UnitType unitType;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(length = 32)
    private String floor;

    @Column(length = 32)
    private String zone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StorageUnitStatus status = StorageUnitStatus.available;

    @Column
    private Instant availableFrom;

    @Column
    private Instant lastReleasedAt;

    @Version
    private long version;
}
