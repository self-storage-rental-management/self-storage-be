package com.storagehub.api.reservation;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.GoodsCatalogService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager/goods-catalog")
@RequiredArgsConstructor
public class ManagerGoodsCatalogController {
    public record ReorderRequest(java.util.List<UUID> orderedIds) {}
    private final ActorContext actors;
    private final GoodsCatalogService catalog;

    @GetMapping
    public ApiResponse<GoodsCatalogResponse> getCatalog() {
        return new ApiResponse<>(catalog.managerCatalog(actors.required()), CorrelationIdContext.current());
    }

    @PatchMapping("/categories/{categoryId}")
    public ApiResponse<GoodsCatalogResponse.Category> updateCategory(
        @PathVariable UUID categoryId,
        @Valid @RequestBody GoodsCatalogCategoryUpdateRequest request
    ) {
        return new ApiResponse<>(catalog.updateCategory(actors.required(), categoryId, request), CorrelationIdContext.current());
    }

    @PostMapping("/categories")
    public ApiResponse<GoodsCatalogResponse.Category> addCategory(
        @Valid @RequestBody GoodsCatalogCategoryUpdateRequest request
    ) {
        return new ApiResponse<>(catalog.addCategory(actors.required(), request), CorrelationIdContext.current());
    }

    @PostMapping("/categories/{categoryId}/materials")
    public ApiResponse<GoodsCatalogResponse.Material> addMaterial(
        @PathVariable UUID categoryId,
        @Valid @RequestBody GoodsCatalogMaterialRequest request
    ) {
        return new ApiResponse<>(catalog.addMaterial(actors.required(), categoryId, request), CorrelationIdContext.current());
    }

    @PatchMapping("/materials/{materialId}")
    public ApiResponse<GoodsCatalogResponse.Material> updateMaterial(
        @PathVariable UUID materialId,
        @Valid @RequestBody GoodsCatalogMaterialRequest request
    ) {
        return new ApiResponse<>(catalog.updateMaterial(actors.required(), materialId, request), CorrelationIdContext.current());
    }

    @PatchMapping("/categories/order")
    public ApiResponse<GoodsCatalogResponse> reorderCategories(@RequestBody ReorderRequest request) {
        return new ApiResponse<>(catalog.reorderCategories(actors.required(), request.orderedIds()), CorrelationIdContext.current());
    }

    @PostMapping("/ordering/categories")
    public ApiResponse<GoodsCatalogResponse> saveCategoryOrder(@RequestBody ReorderRequest request) {
        return reorderCategories(request);
    }

    @PostMapping("/ordering/categories/{categoryId}/materials")
    public ApiResponse<GoodsCatalogResponse.Category> saveMaterialOrder(@PathVariable UUID categoryId, @RequestBody ReorderRequest request) {
        return reorderMaterials(categoryId, request);
    }

    @PatchMapping("/categories/{categoryId}/materials/order")
    public ApiResponse<GoodsCatalogResponse.Category> reorderMaterials(@PathVariable UUID categoryId, @RequestBody ReorderRequest request) {
        return new ApiResponse<>(catalog.reorderMaterials(actors.required(), categoryId, request.orderedIds()), CorrelationIdContext.current());
    }
}
