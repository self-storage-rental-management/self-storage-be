package com.storagehub.domain.repo;

import com.storagehub.domain.model.GoodsCatalogStatus;
import com.storagehub.domain.model.GoodsTypeCatalog;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoodsTypeCatalogRepository extends JpaRepository<GoodsTypeCatalog, UUID> {
    Optional<GoodsTypeCatalog> findByCode(String code);
    List<GoodsTypeCatalog> findAllByOrderByCodeAsc();
    List<GoodsTypeCatalog> findAllByStatusOrderByCodeAsc(GoodsCatalogStatus status);
    List<GoodsTypeCatalog> findAllByOrderBySortOrderAscCodeAsc();
    List<GoodsTypeCatalog> findAllByStatusOrderBySortOrderAscCodeAsc(GoodsCatalogStatus status);
}
