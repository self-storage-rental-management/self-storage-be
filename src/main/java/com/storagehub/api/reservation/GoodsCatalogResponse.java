package com.storagehub.api.reservation;

import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.GoodsCatalogStatus;
import java.util.List;
import java.util.UUID;

public record GoodsCatalogResponse(List<Category> categories) {
    public record Category(UUID id, String code, GoodsCategory reservationCategory, String label, GoodsCatalogStatus status, List<Material> materials, boolean custom, int sortOrder) {}
    public record Material(UUID id, String code, String label, boolean fragile, GoodsCatalogStatus status, int sortOrder) {}
}
