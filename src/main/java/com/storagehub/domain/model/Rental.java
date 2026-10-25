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
import lombok.Setter;

@Entity
@Table(name = "rentals")
@Getter
@Setter
public class Rental extends BaseEntity {

    public Rental() {
        super();
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facility_id", nullable = false)
    private Facility facility;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "storage_unit_id", nullable = false)
    private StorageUnit storageUnit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RentalStatus status = RentalStatus.active;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate contractEndDate;

    /** Actual handover instant. Contract responsibilities apply from this moment. */
    @Column
    private Instant setupStartedAt;

    /** Exclusive setup boundary. The paid rental becomes active at this instant. */
    @Column
    private Instant setupEndsAt;

    @Column
    private Instant termsEffectiveAt;

    /** Customer-declared completion; it never shortens the free setup window. */
    @Column
    private Instant moveInCompletedAt;

    @Column
    private Instant inventoryVerifiedAt;

    @Column
    private Instant inventoryDueAt;

    @Column(nullable = false)
    private boolean inventoryPending;

    /** Setup exits are reviewed by Business; no automatic refund is made. */
    @Column(nullable = false)
    private boolean setupExitReviewRequired;

    @Column
    private Instant setupReminderTwoDaysAt;

    @Column
    private Instant setupReminderLastDayAt;

    @Column
    private Instant inventoryEscalatedAt;

    @Column
    private Instant inventoryTaskCreatedAt;

    @Column
    private Instant actualReturnedAt;

    @Column
    private Instant completedAt;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal monthlyPrice;
}
