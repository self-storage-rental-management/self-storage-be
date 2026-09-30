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
@Table(name = "reservation_goods_items")
@Getter
@Setter
@NoArgsConstructor
public class ReservationGoodsItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private GoodsCategory category;

    @Column(length = 160)
    private String customGoodsName;

    @Column(length = 120)
    private String materialName;

    @Column(length = 120)
    private String customMaterial;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal lengthCm;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal widthCm;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal heightCm;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal weightKg;

    @Column(nullable = false)
    private boolean fragile;

    @Column(length = 1000)
    private String customerNote;

    @Column(nullable = false)
    private boolean requiresStaffReview;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GoodsReviewStatus reviewStatus = GoodsReviewStatus.NOT_REQUIRED;

    @Column(length = 1000)
    private String reviewNote;
}
