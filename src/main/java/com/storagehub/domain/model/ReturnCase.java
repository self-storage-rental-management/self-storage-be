package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "return_cases")
@Getter
@Setter
@NoArgsConstructor
public class ReturnCase extends BaseEntity {

    @Column(nullable = false)
    private boolean setupExitRequested;

    @Column(nullable = false)
    private boolean setupEarlyExit;

    @Column
    private Instant setupReturnedAt;

    @Column(length = 50)
    private String setupEarlyExitPolicyVersion;

    @Column(precision = 14, scale = 2)
    private BigDecimal rentalRefundCredit;

    @Column(precision = 14, scale = 2)
    private BigDecimal earlyTerminationFee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rental_id", nullable = false)
    private Rental rental;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facility_id", nullable = false)
    private Facility facility;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "storage_unit_id", nullable = false)
    private StorageUnit storageUnit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by", nullable = false)
    private User requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private ReturnCaseStatus status = ReturnCaseStatus.requested;

    @Enumerated(EnumType.STRING)
    @Column(name = "return_type", nullable = false, length = 32)
    private ReturnType returnType = ReturnType.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_mode", nullable = false, length = 20)
    private SettlementMode settlementMode = SettlementMode.AUTO;

    @Column(nullable = false)
    private Instant requestedAt;

    @Column(nullable = false)
    private LocalDate scheduledDate;

    @Column(columnDefinition = "TEXT")
    private String customerNotes;

    // Inspection
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inspected_by")
    private User inspectedBy;

    @Column
    private Instant inspectedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private InventoryMatch inventoryMatch;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private DamageClassification damageClassification;

    @Column(columnDefinition = "TEXT")
    private String inspectionNotes;

    @Column(columnDefinition = "TEXT")
    private String evidencePhotosJson;

    @Column(nullable = false)
    private boolean returnedKey = false;

    @Column(nullable = false)
    private boolean returnedCard = false;

    @Column(nullable = false)
    private boolean returnedLock = false;

    /** Whether this return was requested after the rental had become ACTIVE. */
    @Column(nullable = false)
    private boolean activeReturnRepricingRequired = false;

    /** Old reservations without a tier snapshot require an explicit Business decision. */
    @Column(name = "legacy_pricing_review_required", nullable = false)
    private boolean legacyPricingReviewRequired = false;

    @Column(name = "legacy_pricing_snapshot_json", columnDefinition = "LONGTEXT")
    private String legacyPricingSnapshotJson;

    @Column(name = "legacy_pricing_evidence_file_id", columnDefinition = "BINARY(16)")
    private java.util.UUID legacyPricingEvidenceFileId;

    @Column(name = "legacy_pricing_decision_reason", columnDefinition = "TEXT")
    private String legacyPricingDecisionReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private StorageUnitStatus proposedUnitStatus = StorageUnitStatus.available;

    // Settlement fees & deposit
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal depositAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal damageFee = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal cleaningFee = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal lostItemFee = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal overdueFee = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal outstandingFee = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalDeductions = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal netRefundAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amountDueFromCustomer = BigDecimal.ZERO;

    /** Rental-only charge/refund; the security deposit remains separate. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal earlyTerminationCharge = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal rentalRefundAmount = BigDecimal.ZERO;

    @Column
    private Integer overdueDays;

    // Customer Decision
    @Column(nullable = false)
    private boolean customerConfirmed = false;

    @Column
    private Instant customerConfirmedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CustomerDecision customerDecision;

    @Column(columnDefinition = "TEXT")
    private String customerDecisionNote;

    // Manager Review
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column
    private Instant reviewedAt;

    @Column(columnDefinition = "TEXT")
    private String managerResolutionNote;

    // Settlement completion & payment / refund
    @Column(length = 120)
    private String settlementPaymentId;

    @Column
    private Instant settlementPaidAt;

    @Column
    private Instant completedAt;
}
