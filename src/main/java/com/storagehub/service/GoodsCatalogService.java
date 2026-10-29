package com.storagehub.service;

import com.storagehub.api.reservation.GoodsCatalogResponse;
import com.storagehub.api.reservation.GoodsCatalogCategoryUpdateRequest;
import com.storagehub.api.reservation.GoodsCatalogMaterialRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.GoodsCatalogStatus;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.GoodsMaterialCatalog;
import com.storagehub.domain.model.GoodsTypeCatalog;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.repo.GoodsMaterialCatalogRepository;
import com.storagehub.domain.repo.GoodsTypeCatalogRepository;
import com.storagehub.security.ActorPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GoodsCatalogService {
    private record Definition(String label, List<String> normal, List<String> fragile) {}

    private static final Map<GoodsCategory, Definition> DEFAULTS = Map.ofEntries(
        entry(GoodsCategory.FURNITURE, "Nội thất", "Gỗ|Kim loại|Nhựa|Vải|Da|Kính/Thủy tinh|Đá", ""),
        entry(GoodsCategory.KITCHENWARE, "Đồ dùng nhà bếp", "Inox|Gang|Nhôm|Nhựa|Gỗ|Kính/Thủy tinh|Gốm/Sứ", ""),
        entry(GoodsCategory.DECOR, "Vật dụng trang trí", "Gỗ|Kim loại|Nhựa|Vải|Kính/Thủy tinh|Gốm/Sứ|Đá", ""),
        entry(GoodsCategory.DOCUMENTS, "Sách & hồ sơ", "Giấy|Carton", ""),
        entry(GoodsCategory.TOYS, "Đồ chơi", "Nhựa|Gỗ|Vải|Cao su|Kim loại", ""),
        entry(GoodsCategory.SPORTS, "Dụng cụ thể thao", "Kim loại|Cao su|Da|Vải|Composite|Sợi carbon", ""),
        entry(GoodsCategory.MUSICAL_INSTRUMENTS, "Nhạc cụ", "Gỗ|Kim loại", ""),
        entry(GoodsCategory.FINE_ART, "Tranh & tượng", "Giấy|Canvas|Gỗ|Kim loại|Đá|Kính/Thủy tinh|Gốm/Sứ", ""),
        entry(GoodsCategory.ELECTRONICS, "Đồ điện & điện tử", "", ""),
        entry(GoodsCategory.CAMERA_EQUIPMENT, "Máy ảnh & máy quay", "", ""),
        entry(GoodsCategory.OTHER, "Hàng hóa khác", "", "")
    );
    private static final Set<GoodsCategory> OPTIONAL_MATERIAL_CATEGORIES = Set.of(
        GoodsCategory.ELECTRONICS, GoodsCategory.CAMERA_EQUIPMENT, GoodsCategory.MUSICAL_INSTRUMENTS
    );

    private final GoodsTypeCatalogRepository typeRepository;
    private final GoodsMaterialCatalogRepository materialRepository;
    private final AdminAuthorizationService authorization;
    private final AuditLogService audit;
    private final ApplicationEventPublisher events;

    static String historicalCategoryName(com.storagehub.domain.model.ReservationGoodsItem item) {
        if (item.getCategoryName() != null) return item.getCategoryName();
        if (item.getCustomGoodsName() != null) return item.getCustomGoodsName();
        Definition definition = DEFAULTS.get(item.getCategory());
        return definition == null ? "Loại hàng đã khai báo" : definition.label();
    }

    @Transactional(readOnly = true)
    public GoodsCatalogResponse catalog() {
        return response(typeRepository.findAllByStatusOrderBySortOrderAscCodeAsc(GoodsCatalogStatus.ACTIVE), true);
    }

    @Transactional(readOnly = true)
    public GoodsCatalogResponse managerCatalog(ActorPrincipal actor) {
        requireManager(actor);
        return response(typeRepository.findAllByOrderBySortOrderAscCodeAsc(), false);
    }

    @Transactional
    public GoodsCatalogResponse.Category addCategory(ActorPrincipal actor, GoodsCatalogCategoryUpdateRequest request) {
        requireManager(actor);
        GoodsTypeCatalog type = new GoodsTypeCatalog();
        type.setCode("CAT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        type.setDisplayName(required(request.label(), "label"));
        type.setStatus(request.status());
        int nextOrder = typeRepository.findAllByOrderBySortOrderAscCodeAsc().stream()
            .filter(value -> !"OTHER".equals(value.getCode()))
            .mapToInt(GoodsTypeCatalog::getSortOrder).max().orElse(0) + 10;
        type.setSortOrder(nextOrder);
        GoodsTypeCatalog saved = typeRepository.saveAndFlush(type);
        var result = toCategory(saved, false);
        audit.recordMutation("GOODS_CATEGORY_CREATED", "GoodsTypeCatalog", saved.getId(), null, null, result);
        events.publishEvent(new GoodsCatalogEvents.Changed());
        return result;
    }

    @Transactional
    public GoodsCatalogResponse.Category updateCategory(ActorPrincipal actor, UUID id, GoodsCatalogCategoryUpdateRequest request) {
        requireManager(actor);
        GoodsTypeCatalog type = typeRepository.findById(id).orElseThrow(() -> ApiExceptions.notFound("Goods category not found"));
        var before = toCategory(type, false);
        type.setDisplayName(required(request.label(), "label"));
        type.setStatus(request.status());
        GoodsTypeCatalog saved = typeRepository.saveAndFlush(type);
        var after = toCategory(saved, false);
        audit.recordMutation("GOODS_CATEGORY_UPDATED", "GoodsTypeCatalog", id, null, before, after);
        events.publishEvent(new GoodsCatalogEvents.Changed());
        return after;
    }

    @Transactional
    public GoodsCatalogResponse.Material addMaterial(ActorPrincipal actor, UUID categoryId, GoodsCatalogMaterialRequest request) {
        requireManager(actor);
        GoodsTypeCatalog type = typeRepository.findById(categoryId).orElseThrow(() -> ApiExceptions.notFound("Goods category not found"));
        String label = required(request.label(), "label");
        if (materialRepository.existsByGoodsType_IdAndDisplayNameIgnoreCase(categoryId, label)) {
            throw ApiExceptions.conflict("Material already exists in this goods category");
        }
        GoodsMaterialCatalog material = new GoodsMaterialCatalog();
        material.setGoodsType(type);
        material.setCode("MAT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        material.setDisplayName(label);
        material.setFragile(request.fragile());
        material.setStatus(request.status());
        material.setSortOrder(materialRepository.findAllByGoodsType_IdOrderBySortOrderAscDisplayNameAsc(categoryId)
            .stream().mapToInt(GoodsMaterialCatalog::getSortOrder).max().orElse(0) + 10);
        GoodsMaterialCatalog saved = materialRepository.saveAndFlush(material);
        var result = toMaterial(saved);
        audit.recordMutation("GOODS_MATERIAL_CREATED", "GoodsMaterialCatalog", saved.getId(), null, null, result);
        events.publishEvent(new GoodsCatalogEvents.Changed());
        return result;
    }

    @Transactional
    public GoodsCatalogResponse.Material updateMaterial(ActorPrincipal actor, UUID id, GoodsCatalogMaterialRequest request) {
        requireManager(actor);
        GoodsMaterialCatalog material = materialRepository.findById(id).orElseThrow(() -> ApiExceptions.notFound("Goods material not found"));
        var before = toMaterial(material);
        String label = required(request.label(), "label");
        if (!material.getDisplayName().equalsIgnoreCase(label)
            && materialRepository.existsByGoodsType_IdAndDisplayNameIgnoreCase(material.getGoodsType().getId(), label)) {
            throw ApiExceptions.conflict("Material already exists in this goods category");
        }
        material.setDisplayName(label);
        material.setFragile(request.fragile());
        material.setStatus(request.status());
        GoodsMaterialCatalog saved = materialRepository.saveAndFlush(material);
        var after = toMaterial(saved);
        audit.recordMutation("GOODS_MATERIAL_UPDATED", "GoodsMaterialCatalog", id, null, before, after);
        events.publishEvent(new GoodsCatalogEvents.Changed());
        return after;
    }

    @Transactional
    public GoodsCatalogResponse reorderCategories(ActorPrincipal actor, List<UUID> orderedIds) {
        requireManager(actor);
        List<GoodsTypeCatalog> all = typeRepository.findAllByOrderBySortOrderAscCodeAsc();
        verifyCompleteOrder(orderedIds, all.stream().map(GoodsTypeCatalog::getId).toList());
        GoodsTypeCatalog custom = all.stream().filter(value -> "OTHER".equals(value.getCode())).findFirst().orElse(null);
        List<UUID> normalized = orderedIds.stream().filter(id -> custom == null || !custom.getId().equals(id)).toList();
        for (int index = 0; index < normalized.size(); index++) {
            UUID id = normalized.get(index);
            all.stream().filter(value -> value.getId().equals(id)).findFirst().orElseThrow().setSortOrder((index + 1) * 10);
        }
        if (custom != null) custom.setSortOrder(Integer.MAX_VALUE);
        typeRepository.saveAllAndFlush(all);
        audit.recordMutation("GOODS_CATEGORIES_REORDERED", "GoodsTypeCatalog",
            orderedIds.isEmpty() ? actor.userId() : orderedIds.get(0), null, null, normalized);
        events.publishEvent(new GoodsCatalogEvents.Changed());
        return managerCatalog(actor);
    }

    @Transactional
    public GoodsCatalogResponse.Category reorderMaterials(ActorPrincipal actor, UUID categoryId, List<UUID> orderedIds) {
        requireManager(actor);
        GoodsTypeCatalog type = typeRepository.findById(categoryId).orElseThrow(() -> ApiExceptions.notFound("Goods category not found"));
        List<GoodsMaterialCatalog> all = materialRepository.findAllByGoodsType_IdOrderBySortOrderAscDisplayNameAsc(categoryId);
        verifyCompleteOrder(orderedIds, all.stream().map(GoodsMaterialCatalog::getId).toList());
        for (int index = 0; index < orderedIds.size(); index++) {
            UUID id = orderedIds.get(index);
            all.stream().filter(value -> value.getId().equals(id)).findFirst().orElseThrow().setSortOrder((index + 1) * 10);
        }
        materialRepository.saveAllAndFlush(all);
        audit.recordMutation("GOODS_MATERIALS_REORDERED", "GoodsTypeCatalog", categoryId, null, null, orderedIds);
        events.publishEvent(new GoodsCatalogEvents.Changed());
        return toCategory(type, false);
    }

    private void verifyCompleteOrder(List<UUID> orderedIds, List<UUID> expectedIds) {
        if (orderedIds == null || orderedIds.stream().anyMatch(java.util.Objects::isNull)
            || new java.util.HashSet<>(orderedIds).size() != orderedIds.size()) {
            throw ApiExceptions.validation("Danh sách sắp xếp chứa mục trùng hoặc thiếu mã. Vui lòng tải lại danh mục.", null);
        }
        if (!new java.util.HashSet<>(orderedIds).equals(new java.util.HashSet<>(expectedIds)))
            throw ApiExceptions.conflict("Danh mục đã thay đổi trong lúc sắp xếp. Vui lòng tải lại danh mục rồi thử lại.");
    }

    @Transactional(readOnly = true)
    public void validate(GoodsCategory category, String materialName, int index) {
        GoodsTypeCatalog type = typeRepository.findByCode(category.name())
            .filter(value -> value.getStatus() == GoodsCatalogStatus.ACTIVE)
            .orElseThrow(() -> ApiExceptions.validation("goodsItems[" + index + "].category is not active", null));
        if (category == GoodsCategory.OTHER || materialOptional(category, materialName)) return;
        String requested = materialName == null ? "" : materialName.trim();
        boolean supported = materialRepository.findAllByGoodsType_IdAndStatusOrderByDisplayNameAsc(type.getId(), GoodsCatalogStatus.ACTIVE)
            .stream().anyMatch(value -> value.getDisplayName().equalsIgnoreCase(requested));
        if (!supported) throw ApiExceptions.validation(
            "goodsItems[" + index + "].materialName is not allowed for category " + category, null);
    }

    @Transactional(readOnly = true, noRollbackFor = ApiException.class)
    public void validateForUnitType(UnitType unitType, GoodsCategory category, String materialName, int index) {
        validate(category, materialName, index);
        try {
            var scope = new com.fasterxml.jackson.databind.ObjectMapper().readTree(unitType.getAcceptedGoodsJson());
            var type = typeRepository.findByCode(category.name())
                .orElseThrow(() -> ApiExceptions.validation("Loại hàng chưa được cấu hình cho loại kho đã chọn.", null));
            var categoryIds = scope.path("categoryIds");
            boolean categoryAllowed = !categoryIds.isArray() || categoryIds.isEmpty();
            for (var id : categoryIds) if (type.getId().toString().equals(id.asText())) categoryAllowed = true;
            if (!categoryAllowed) throw ApiExceptions.validation("goodsItems[" + index + "] không thuộc nhóm hàng loại kho này nhận.", null);
            if (category == GoodsCategory.OTHER || materialOptional(category, materialName)) return;
            var material = materialRepository.findAllByGoodsType_IdAndStatusOrderByDisplayNameAsc(type.getId(), GoodsCatalogStatus.ACTIVE)
                .stream().filter(value -> value.getDisplayName().equalsIgnoreCase(materialName.trim())).findFirst()
                .orElseThrow(() -> ApiExceptions.validation("Vật liệu không còn được áp dụng.", null));
            var materialIds = scope.path("materialIds");
            boolean materialAllowed = !materialIds.isArray() || materialIds.isEmpty();
            for (var id : materialIds) if (material.getId().toString().equals(id.asText())) materialAllowed = true;
            if (!materialAllowed) throw ApiExceptions.validation("goodsItems[" + index + "] có vật liệu không được nhận tại loại kho này.", null);
        } catch (com.storagehub.common.api.ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw ApiExceptions.conflict("Loại kho chưa có cấu hình hàng hóa hợp lệ.");
        }
    }

    @Transactional(readOnly = true, noRollbackFor = ApiException.class)
    public void validateForUnitType(UnitType unitType, com.storagehub.api.reservation.GoodsItemRequest item, int index) {
        GoodsTypeCatalog type = (item.getCatalogCategoryId() == null
            ? typeRepository.findByCode(item.getCategory().name())
            : typeRepository.findById(item.getCatalogCategoryId()))
            .filter(value -> value.getStatus() == GoodsCatalogStatus.ACTIVE)
            .orElseThrow(() -> ApiExceptions.validation("Loại hàng không còn được áp dụng.", null));
        try {
            var scope = new com.fasterxml.jackson.databind.ObjectMapper().readTree(unitType.getAcceptedGoodsJson());
            var categoryIds = scope.path("categoryIds");
            boolean allowed = !categoryIds.isArray() || categoryIds.isEmpty();
            for (var id : categoryIds) if (type.getId().toString().equals(id.asText())) allowed = true;
            if (!allowed) throw ApiExceptions.validation("Loại kho này không nhận loại hàng đã chọn.", null);
            item.setCatalogCategoryId(type.getId());
            item.setCategoryCode(type.getCode());
            item.setCategoryName(type.getDisplayName());
            item.setCategory(reservationCategory(type.getCode()));
            item.setMaterialCode(null);
            if ("OTHER".equals(type.getCode())) {
                item.setCatalogMaterialId(null);
                return;
            }
            if (item.getCatalogMaterialId() == null && materialOptional(item.getCategory(), item.getMaterialName())) {
                item.setCatalogMaterialId(null);
                item.setMaterialName(null);
                return;
            }
            GoodsMaterialCatalog material = materialRepository
                .findAllByGoodsType_IdAndStatusOrderByDisplayNameAsc(type.getId(), GoodsCatalogStatus.ACTIVE)
                .stream().filter(value -> item.getCatalogMaterialId() != null
                    ? value.getId().equals(item.getCatalogMaterialId())
                    : value.getDisplayName().equalsIgnoreCase(item.getMaterialName() == null ? "" : item.getMaterialName().trim()))
                .findFirst().orElseThrow(() -> ApiExceptions.validation("Vật liệu không thuộc loại hàng đã chọn.", null));
            var materialIds = scope.path("materialIds");
            allowed = !materialIds.isArray() || materialIds.isEmpty();
            for (var id : materialIds) if (material.getId().toString().equals(id.asText())) allowed = true;
            if (!allowed) throw ApiExceptions.validation("Loại kho này không nhận vật liệu đã chọn.", null);
            item.setCatalogMaterialId(material.getId());
            item.setMaterialCode(material.getCode());
            item.setMaterialName(material.getDisplayName());
        } catch (com.storagehub.common.api.ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw ApiExceptions.conflict("Loại kho chưa có cấu hình hàng hóa hợp lệ.");
        }
    }

    @Transactional
    public void seedDefaults() {
        for (GoodsCategory code : GoodsCategory.values()) {
            if (!DEFAULTS.containsKey(code)) continue;
            // Seed only new categories; never recreate renamed or archived manager data.
            if (typeRepository.findByCode(code.name()).isPresent()) continue;
            Definition definition = DEFAULTS.get(code);
            GoodsTypeCatalog type = typeRepository.findByCode(code.name()).orElseGet(() -> {
                GoodsTypeCatalog created = new GoodsTypeCatalog();
                created.setCode(code.name());
                created.setDisplayName(definition.label());
                created.setStatus(GoodsCatalogStatus.ACTIVE);
                created.setSortOrder(code == GoodsCategory.OTHER ? Integer.MAX_VALUE : (code.ordinal() + 1) * 10);
                return typeRepository.saveAndFlush(created);
            });
            Stream.concat(definition.normal().stream().map(name -> Map.entry(name, false)),
                    definition.fragile().stream().map(name -> Map.entry(name, true)))
                .filter(item -> !materialRepository.existsByGoodsType_IdAndDisplayNameIgnoreCase(type.getId(), item.getKey()))
                .forEach(item -> {
                    GoodsMaterialCatalog material = new GoodsMaterialCatalog();
                    material.setGoodsType(type);
                    material.setCode("MAT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
                    material.setDisplayName(item.getKey());
                    material.setFragile(item.getValue());
                    material.setStatus(GoodsCatalogStatus.ACTIVE);
                    material.setSortOrder(materialRepository.findAllByGoodsType_IdOrderBySortOrderAscDisplayNameAsc(type.getId())
                        .stream().mapToInt(GoodsMaterialCatalog::getSortOrder).max().orElse(0) + 10);
                    materialRepository.save(material);
                });
        }
    }

    private GoodsCatalogResponse response(List<GoodsTypeCatalog> types, boolean activeOnly) {
        return new GoodsCatalogResponse(types.stream().sorted(java.util.Comparator
            .comparing((GoodsTypeCatalog type) -> "OTHER".equals(type.getCode()))
            .thenComparingInt(GoodsTypeCatalog::getSortOrder).thenComparing(GoodsTypeCatalog::getCode))
            .map(type -> toCategory(type, activeOnly)).toList());
    }

    private GoodsCatalogResponse.Category toCategory(GoodsTypeCatalog type, boolean activeOnly) {
        List<GoodsMaterialCatalog> materials = activeOnly
            ? materialRepository.findAllByGoodsType_IdAndStatusOrderBySortOrderAscDisplayNameAsc(type.getId(), GoodsCatalogStatus.ACTIVE)
            : materialRepository.findAllByGoodsType_IdOrderBySortOrderAscDisplayNameAsc(type.getId());
        GoodsCategory reservationCategory = reservationCategory(type.getCode());
        return new GoodsCatalogResponse.Category(type.getId(), type.getCode(), reservationCategory, type.getDisplayName(), type.getStatus(),
            materials.stream().map(this::toMaterial).toList(), "OTHER".equals(type.getCode()), type.getSortOrder());
    }

    private GoodsCatalogResponse.Material toMaterial(GoodsMaterialCatalog material) {
        return new GoodsCatalogResponse.Material(material.getId(), material.getCode(), material.getDisplayName(),
            material.isFragile(), material.getStatus(), material.getSortOrder());
    }

    private void requireManager(ActorPrincipal actor) {
        if (!actor.hasRole(RoleCode.MANAGER)) throw ApiExceptions.forbidden("Manager role is required");
        authorization.require(actor, SystemPermission.MANAGE_INVENTORY);
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw ApiExceptions.validation(field + " is required", null);
        return value.trim();
    }

    private static Map.Entry<GoodsCategory, Definition> entry(GoodsCategory category, String label, String normal, String fragile) {
        return Map.entry(category, new Definition(label, split(normal), split(fragile)));
    }

    private static List<String> split(String values) {
        return values.isBlank() ? List.of() : List.of(values.split("\\|"));
    }

    private static boolean materialOptional(GoodsCategory category, String materialName) {
        return OPTIONAL_MATERIAL_CATEGORIES.contains(category)
            && (materialName == null || materialName.isBlank());
    }

    private static GoodsCategory reservationCategory(String code) {
        try { return GoodsCategory.valueOf(code); }
        catch (IllegalArgumentException ignored) { return GoodsCategory.OTHER; }
    }

}
