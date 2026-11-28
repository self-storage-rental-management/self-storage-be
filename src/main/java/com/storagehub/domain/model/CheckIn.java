package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@SecondaryTable(name = "check_in_identity_display", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
@Table(
    name = "check_ins",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_check_in_reservation",
        columnNames = "reservation_id"
    )
)
@Getter
@Setter
@NoArgsConstructor
public class CheckIn extends BaseEntity {
    @jakarta.persistence.Lob
    @Column(columnDefinition = "LONGTEXT")
    private String emergencyContactsSnapshot;


    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rental_id")
    private Rental rental;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performed_by", nullable = false)
    private User performedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_staff_id")
    private User assignedStaff;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CheckInStatus status = CheckInStatus.scheduled;

    @Column
    private Instant scheduledAt;

    @Column
    private Instant checkedInAt;

    @Column(name = "handover_record_json", columnDefinition = "LONGTEXT")
    private String handoverRecordJson;

    @Column(name = "customer_receipt_confirmed_at")
    private Instant customerReceiptConfirmedAt;

    @Column
    private Instant identityVerifiedAt;

    @Column(length = 30)
    private String verifiedIdentityDocument;

    @Column(table = "check_in_identity_display", length = 12)
    private String declaredIdentityMasked;

    @Column(table = "check_in_identity_display")
    private Boolean identityCorrected;
    @Column(table = "check_in_identity_display", nullable = false)
    private int identityFailedAttempts;
    @Column(table = "check_in_identity_display", nullable = false)
    private int identityCorrectionFailedAttempts;
    @Column(table = "check_in_identity_display", length = 12)
    private String approvedIdentityCorrection;

    @Column(length = 1000)
    private String identityVerificationNote;

    @Column
    private Instant contractSignedAt;

    @Column(length = 36)
    private String contractEvidenceReference;

    @Column
    private Instant paymentCollectedAt;

    @Column
    private Instant setupStartedAt;

    @Column
    private Instant setupDueAt;

    @Column
    private Instant setupCompletedAt;

    private Instant inventoryAppointmentAt;
    private Instant inventoryArrivedAt;
    private Instant inventoryIdentityVerifiedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_identity_verified_by")
    private User inventoryIdentityVerifiedBy;

    @Column(name = "inventory_identity_photo_id")
    private java.util.UUID inventoryIdentityPhotoId;

    @Column
    private Instant inventoryVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "inventory_resolution_status", nullable = false, length = 32)
    private InventoryResolutionStatus inventoryResolutionStatus = InventoryResolutionStatus.NOT_STARTED;

    @Column(name = "inventory_actual_json", columnDefinition = "LONGTEXT")
    private String inventoryActualJson;

    @Column(name = "inventory_draft_json", columnDefinition = "LONGTEXT")
    private String inventoryDraftJson;
    @Column(name = "inventory_draft_saved_at")
    private Instant inventoryDraftSavedAt;
    @Column(name = "inventory_draft_revision", nullable = false)
    private long inventoryDraftRevision;


    @Column(name = "inventory_mismatch_json", columnDefinition = "LONGTEXT")
    private String inventoryMismatchJson;

    @Column(name = "inventory_suggested_quantities_json", columnDefinition = "LONGTEXT")
    private String inventorySuggestedQuantitiesJson;

    @Column(name = "inventory_accepted_quantities_json", columnDefinition = "LONGTEXT")
    private String inventoryAcceptedQuantitiesJson;

    @Column(name = "inventory_decision_json", columnDefinition = "LONGTEXT")
    private String inventoryDecisionJson;

    @Column(name = "inventory_decision_at")
    private Instant inventoryDecisionAt;

    @Column(name = "inventory_decision_note", length = 2000)
    private String inventoryDecisionNote;

    public boolean hasPendingInventoryRefusal() {
        return inventoryConfirmation != null && inventoryConfirmation.getInventoryRefusalSubmittedAt() != null
            && inventoryConfirmation.getInventoryRefusalApproved() == null;
    }

    @Column(name = "inventory_full_fit", nullable = false)
    private boolean inventoryFullFit;

    @Column(name = "inventory_partial_fit_available", nullable = false)
    private boolean inventoryPartialFitAvailable;

    @Column(name = "inventory_suggested_package_count")
    private Integer inventorySuggestedPackageCount;

    @Column(name = "inventory_mismatch_summary", length = 2000)
    private String inventoryMismatchSummary;

    @Column(name = "inventory_remainder_removed_at")
    private Instant inventoryRemainderRemovedAt;

    @Column(name = "inventory_remainder_removal_note", columnDefinition = "TEXT")
    private String inventoryRemainderRemovalNote;

    @Column(name = "inventory_remainder_evidence_json", columnDefinition = "LONGTEXT")
    private String inventoryRemainderEvidenceJson;

    /**
     * At-site confirmation: the verified customer is physically present, Staff presented the
     * result, and the agreed arrangement was recorded together with the evidence. The version
     * ties the confirmation to the exact inspection record it approves.
     * <p>
     * Stored in a side table because {@code check_ins} is already at MySQL's row-size limit.
     */
    @jakarta.persistence.OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id", referencedColumnName = "check_in_id", insertable = false, updatable = false)
    private CheckInInventoryConfirmation inventoryConfirmation;

    @Column(length = 160)
    private String lockReference;

    @Column(length = 8000)
    private String checklistJson;

    @Column(length = 1000)
    private String readinessNote;

    @Column(length = 1000)
    private String rejectionReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UnitReleaseDisposition rejectionDisposition;

    private Instant rejectionResolvedAt;

    @Column(length = 1000)
    private String rejectionResolutionNote;

    @Version
    private long version;
}
