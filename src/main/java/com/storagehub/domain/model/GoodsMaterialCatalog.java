package com.storagehub.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "goods_material_catalog", uniqueConstraints = @UniqueConstraint(name = "uk_goods_material_catalog_code", columnNames = "code"))
@Getter @Setter @NoArgsConstructor
public class GoodsMaterialCatalog extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goods_type_id", nullable = false)
    private GoodsTypeCatalog goodsType;

    @Column(nullable = false, updatable = false, length = 24)
    private String code;

    @Column(nullable = false, length = 160)
    private String displayName;

    @Column(nullable = false)
    private boolean fragile;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GoodsCatalogStatus status = GoodsCatalogStatus.ACTIVE;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Version
    private long version;
}
