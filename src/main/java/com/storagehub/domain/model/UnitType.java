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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "unit_types")
@Getter
@Setter
@NoArgsConstructor
public class UnitType extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facility_id", nullable = false)
    private Facility facility;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal lengthM;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal widthM;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal heightM;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal areaM2;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal volumeM3;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal pricePerM3;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal maxLoadKg;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UnitTypeStatus status = UnitTypeStatus.active;
}
