package com.storagehub.domain.repo;

import com.storagehub.domain.model.GoodsCatalogStatus;
import com.storagehub.domain.model.GoodsMaterialCatalog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoodsMaterialCatalogRepository extends JpaRepository<GoodsMaterialCatalog, UUID> {
    List<GoodsMaterialCatalog> findAllByGoodsType_IdOrderByDisplayNameAsc(UUID goodsTypeId);
    List<GoodsMaterialCatalog> findAllByGoodsType_IdAndStatusOrderByDisplayNameAsc(UUID goodsTypeId, GoodsCatalogStatus status);
    boolean existsByGoodsType_IdAndDisplayNameIgnoreCase(UUID goodsTypeId, String displayName);
    List<GoodsMaterialCatalog> findAllByGoodsType_IdOrderBySortOrderAscDisplayNameAsc(UUID goodsTypeId);
    List<GoodsMaterialCatalog> findAllByGoodsType_IdAndStatusOrderBySortOrderAscDisplayNameAsc(UUID goodsTypeId, GoodsCatalogStatus status);
}
