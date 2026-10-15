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
@Table(name = "damage_incidents")
@Getter
@Setter
@NoArgsConstructor
public class DamageIncident extends BaseEntity {

    @Column(name = "incident_code", nullable = false, unique = true, length = 32)
    private String incidentCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facility_id", nullable = false)
    private Facility facility;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_unit_id")
    private StorageUnit storageUnit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DamageCause cause;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IncidentSeverity severity;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reported_by", nullable = false)
    private User reportedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private IncidentStatus status = IncidentStatus.REPORTED;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @Column(name = "evidence_photos_json", columnDefinition = "TEXT")
    private String evidencePhotosJson;
}
