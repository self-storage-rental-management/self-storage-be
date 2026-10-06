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

    @Column(nullable = false)
    private Instant requestedAt;

    @Column(nullable = false)
    private LocalDate scheduledDate;

    @Column(length = 2000)
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

    @Column(length = 4000)
    private String inspectionNotes;

    @Column(length = 4000)
    private String evidencePhotosJson;

    @Column(nullable = false)
    private boolean returnedKey = false;

    @Column(nullable = false)
    private boolean returnedCard = false;

    @Column(nullable = false)
    private boolean returnedLock = false;

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

    @Column(length = 2000)
    private String customerDecisionNote;

    // Manager Review
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column
    private Instant reviewedAt;

    @Column(length = 2000)
    private String managerResolutionNote;

    // Settlement completion & payment / refund
    @Column(length = 64)
    private String settlementPaymentId;

    @Column
    private Instant settlementPaidAt;

    @Column
    private Instant completedAt;
}
