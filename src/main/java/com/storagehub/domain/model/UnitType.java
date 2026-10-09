package com.storagehub.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "unit_types",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_unit_type_facility_code",
        columnNames = {"facility_id", "code"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class UnitType extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "facility_id", nullable = false)
    private Facility facility;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "length_m", nullable = false, precision = 10, scale = 2)
    private BigDecimal lengthM;

    @Column(name = "width_m", nullable = false, precision = 10, scale = 2)
    private BigDecimal widthM;

    @Column(name = "height_m", nullable = false, precision = 10, scale = 2)
    private BigDecimal heightM;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(length = 1000)
    private String imageUrl;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal maxLoadKg;

    @Column(nullable = false)
    private int rackCount;

    @Column(name = "rack_length_m", nullable = false, precision = 10, scale = 2)
    private BigDecimal rackLengthM;

    @Column(name = "rack_width_m", nullable = false, precision = 10, scale = 2)
    private BigDecimal rackWidthM;

    @Column(name = "rack_height_m", nullable = false, precision = 10, scale = 2)
    private BigDecimal rackHeightM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UnitTypeStatus status = UnitTypeStatus.active;

    @Version
    private long version;

    public BigDecimal getAreaM2() {
        return lengthM.multiply(widthM);
    }

    public BigDecimal getVolumeM3() {
        return getAreaM2().multiply(heightM);
    }
}
