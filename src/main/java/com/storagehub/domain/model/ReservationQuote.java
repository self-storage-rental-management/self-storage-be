package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "reservation_quotes")
@Getter
@Setter
@NoArgsConstructor
public class ReservationQuote extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facility_id", nullable = false)
    private Facility facility;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_type_id", nullable = false)
    private UnitType unitType;

    @Column(nullable = false, length = 30)
    private String pricingPackageCode;

    @Column(nullable = false, length = 50)
    private String policyVersion;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private int rentalMonths;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal discountRate;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal discountAmount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAfterDiscount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal reservationDepositAmount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal securityDepositAmount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal remainingRentalAmount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal dueAtCheckIn;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalInitialObligation;

    @Column(nullable = false)
    private Instant quotedAt;

    @Column(nullable = false)
    private Instant expiresAt;
}
