package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "reservation_pricing_snapshots")
@Getter
@Setter
@NoArgsConstructor
public class ReservationPricingSnapshot extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false, unique = true)
    private Reservation reservation;

    @Column(nullable = false, length = 50)
    private String pricingPackageCode;

    @Column(nullable = false, length = 50)
    private String pricingPolicyVersion;

    @Column(nullable = false)
    private int rentalMonths;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal grossRentalAmount;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal discountRate = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal netRentalAmount;

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
