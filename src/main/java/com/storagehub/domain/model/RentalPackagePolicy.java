package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "rental_package_policies",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_rental_package_facility_code",
        columnNames = {"facility_id", "code"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class RentalPackagePolicy extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facility_id", nullable = false)
    private Facility facility;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int rentalMonths;

    /** Free calendar days granted once, only for the initial move-in setup. */
    @Column(nullable = false)
    private int freeSetupDays;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal discountRate = BigDecimal.ZERO;

    @Column(name = "deposit_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal depositRate = new BigDecimal("0.2000");

    /** Snapshot defaults for early termination; copied into each accepted quote. */
    @Column(name = "early_termination_fee_cap_months", nullable = false)
    private int earlyTerminationFeeCapMonths = 1;

    @Column(name = "early_termination_fee_coefficient", nullable = false, precision = 5, scale = 4)
    private BigDecimal earlyTerminationFeeCoefficient = BigDecimal.ONE;

    @Column(name = "early_termination_notice_days", nullable = false)
    private int earlyTerminationNoticeDays = 14;

    @Column(length = 500)
    private String description;

    @Column
    private Integer rentalYears;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "publication_status", nullable = false, length = 20)
    private PublicationStatus publicationStatus = PublicationStatus.INACTIVE;

    @Column(nullable = false, length = 50)
    private String policyVersion;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    @Column
    private LocalDate effectiveTo;
}
