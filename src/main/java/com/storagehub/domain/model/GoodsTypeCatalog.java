package com.storagehub.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "goods_type_catalog", uniqueConstraints = @UniqueConstraint(name = "uk_goods_type_catalog_code", columnNames = "code"))
@Getter @Setter @NoArgsConstructor
public class GoodsTypeCatalog extends BaseEntity {
    @Column(nullable = false, updatable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 160)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GoodsCatalogStatus status = GoodsCatalogStatus.ACTIVE;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Version
    private long version;
}
