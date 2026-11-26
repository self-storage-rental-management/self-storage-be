package com.storagehub.domain.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "violation_recoveries")
@Getter
@Setter
public class ViolationRecovery extends BaseEntity {
    public enum Reason { SETUP_OVERDUE, INVENTORY_NO_SHOW, INVENTORY_IDENTITY_FAILED, INVENTORY_TIMEOUT, REMOVAL_OVERDUE, HANDOVER_FOLLOW_UP }
    public enum Stage { REVIEW_REQUIRED, AWAITING_ASSIGNMENT, AWAITING_ACCEPTANCE, AWAITING_ATTENDANCE, AWAITING_APPROVAL, APPROVED, IN_PROGRESS, BLOCKED, COMPLETED, CANCELLED }
    public enum Attendance { PRESENT, ABSENT, UNDETERMINED }
    public enum Reply { WILL_ATTEND, CANNOT_ATTEND, DISPUTED, NO_RESPONSE }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rental_id", nullable = false)
    private Rental rental;
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private MaintenanceTask task;
    @Column private UUID sourceReturnId;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Reason reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Stage stage;
    @Enumerated(EnumType.STRING)
    private RentalStatus previousRentalStatus;
    @Enumerated(EnumType.STRING)
    private ReturnCaseStatus previousReturnStatus;
    @Column(nullable = false) private Instant sourceDeadlineAt;
    @Column private Instant requiredAt;
    @Column private Instant scheduledAt;
    @Column private Instant deadlineAt;
    @Column private Instant actionDueAt;
    @Column private Instant responseCheckAt;
    @Column private Instant overdueAt;
    @Column private Instant completedAt;
    @Column private UUID completedBy;
    @Column(nullable = false) private String policyVersion = "RECOVERY_V1";
    @Column private boolean escalated;
    @Column private boolean financePending;
    @Column private boolean financeClosed;
    @Column private Instant pickupDueAt;
    @Column private Instant pickupReviewAt;
    @Column private boolean pickupEscalated;
    @Column(columnDefinition = "LONGTEXT") private String detailsJson;
}
