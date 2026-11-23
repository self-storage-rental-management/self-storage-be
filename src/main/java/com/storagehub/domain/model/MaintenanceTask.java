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
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "maintenance_tasks")
@Getter
@Setter
@NoArgsConstructor
public class MaintenanceTask extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facility_id", nullable = false)
    private Facility facility;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "storage_unit_id", nullable = false)
    private StorageUnit storageUnit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_case_id")
    private ReturnCase returnCase;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rental_id")
    private Rental rental;

    @Column(nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 32)
    private MaintenanceTaskType taskType = MaintenanceTaskType.maintenance;

    @Column(nullable = false, length = 2000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskPriority priority = TaskPriority.medium;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private DamageClassification damageClassification;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by")
    private User reportedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private User assignedTo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recovery_witness_id")
    private User recoveryWitness;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private MaintenanceTaskStatus status = MaintenanceTaskStatus.open;

    @Enumerated(EnumType.STRING)
    @Column(name = "recovery_type", nullable = false, length = 32)
    private MaintenanceRecoveryType recoveryType = MaintenanceRecoveryType.NONE;

    @Column
    private LocalDate dueAt;

    /** Exact deadline used for countdown and escalation. dueAt remains for legacy date filters. */
    @Column
    private Instant deadlineAt;

    @Column(name = "recovery_handover_location", length = 500)
    private String recoveryHandoverLocation;

    @Column
    private Instant startedAt;

    @Column
    private Instant completedAt;

    @Column(length = 4000)
    private String resultReport;

    @Column(length = 4000)
    private String evidencePhotosJson;
}
