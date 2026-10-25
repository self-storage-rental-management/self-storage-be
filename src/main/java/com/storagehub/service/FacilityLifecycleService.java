package com.storagehub.service;

import com.storagehub.api.configuration.UnitTypePricingRequest;
import com.storagehub.api.facility.FacilityResponse;
import com.storagehub.api.facility.ManagerUnitTypeRequest;
import com.storagehub.api.facility.UnitTypeResponse;
import com.storagehub.api.facility.UpdateFacilityRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.domain.repo.UserFacilityScopeRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FacilityLifecycleService {
    private final FacilityRepository facilities;
    private final UnitTypeRepository unitTypes;
    private final StorageUnitRepository units;
    private final RentalPackagePolicyRepository packages;
    private final FacilityScopeService scopes;
    private final AdminAuthorizationService authorization;
    private final AuditLogService audit;
    private final UserFacilityScopeRepository userFacilityScopes;
    private final com.storagehub.domain.repo.ReservationRepository reservations;
    private final NotificationService notifications;
    private final com.storagehub.domain.repo.UnitTypeChangeRequestRepository changes;
    private final com.storagehub.domain.repo.UserRepository users;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.storagehub.domain.repo.GoodsTypeCatalogRepository goodsTypes;
    private final com.storagehub.domain.repo.GoodsMaterialCatalogRepository goodsMaterials;

    private static byte[] bytes(UUID id) {
        if (id == null) return null;
        return java.nio.ByteBuffer.allocate(16)
            .putLong(id.getMostSignificantBits())
            .putLong(id.getLeastSignificantBits())
            .array();
    }

    @Transactional
    public void deleteFacility(ActorPrincipal actor, UUID facilityId) {
        requireBusiness(actor);
        Facility facility = facility(facilityId);
        FacilityResponse before = facilityResponse(facility);
        facility.setStatus(FacilityStatus.inactive);
        facilities.saveAndFlush(facility);
        unitTypes.findByFacility_IdOrderByCodeAsc(facilityId).forEach(type -> type.setStatus(UnitTypeStatus.archived));
        unitTypes.flush();
        audit.recordMutation("FACILITY_ARCHIVED", "Facility", facilityId, facilityId, before, facilityResponse(facility));
    }

    @Transactional
    public FacilityResponse updateFacility(ActorPrincipal actor, UUID facilityId, UpdateFacilityRequest request) {
        requireBusiness(actor);
        Facility facility = facility(facilityId);
        FacilityResponse before = facilityResponse(facility);
        facility.setName(request.name().trim());
        facility.setAddress(request.address().trim());
        facility.setCity(request.city().trim());
        facility.setPhone(clean(request.phone()));
        facility.setAccessHours(clean(request.accessHours()));
        facility.setImageUrl(clean(request.imageUrl()));
        if (request.climateControlled() != null) facility.setClimateControlled(request.climateControlled());
        facility.setSecurityDescription(clean(request.securityDescription()));
        if (request.status() != null && request.status() != facility.getStatus()) {
            if (request.status() == FacilityStatus.active) {
                validateAndActivate(facility);
            } else {
                facility.setStatus(request.status());
            }
        }
        Facility saved = facilities.save(facility);
        FacilityResponse after = facilityResponse(saved);
        audit.recordMutation("FACILITY_UPDATED", "Facility", saved.getId(), saved.getId(), before, after);
        return after;
    }

    @Transactional
    public UnitTypeResponse createUnitType(ActorPrincipal actor, UUID facilityId, ManagerUnitTypeRequest request) {
        requireManager(actor, facilityId);
        Facility facility = facility(facilityId);
        if (facility.getStatus() == FacilityStatus.inactive) {
            throw ApiExceptions.conflict("Inactive facility cannot receive unit types");
        }
        String code = generateUnitTypeCode(facilityId);
        UnitType type = new UnitType();
        type.setFacility(facility);
        applyTechnical(type, request);
        type.setCode(code);
        type.setMonthlyPrice(BigDecimal.ZERO);
        type.setSecurityDepositAmount(BigDecimal.ZERO);
        type.setStatus(UnitTypeStatus.awaiting_pricing);
        UnitType saved = unitTypes.saveAndFlush(type);
        createPhysicalUnits(saved, request.physicalUnitCount(), request.floor(), request.zone());
        UnitTypeResponse response = unitTypeResponse(saved);
        audit.recordMutation("UNIT_TYPE_CREATED_BY_MANAGER", "UnitType", saved.getId(), facilityId, null, response);
        return response;
    }

    @Transactional
    public UnitTypeResponse updateUnitType(ActorPrincipal actor, UUID unitTypeId, ManagerUnitTypeRequest request) {
        UnitType type = unitTypes.findByIdForUpdate(unitTypeId)
            .orElseThrow(() -> ApiExceptions.notFound("Unit type was not found"));
        UUID facilityId = type.getFacility().getId();
        requireManager(actor, facilityId);
        if (request.code() != null && !request.code().isBlank()
            && !type.getCode().equalsIgnoreCase(request.code().trim())) {
            throw ApiExceptions.conflict("Không thể đổi mã loại kho đã tạo.");
        }
        boolean technicalChanged = technicalChanged(type, request);
        boolean requiresRepricing = technicalChanged || goodsScopeChanged(type, request);
        if (changes.existsByUnitTypeIdAndStatus(unitTypeId, "PENDING")) {
            throw ApiExceptions.conflict("Loại kho đang có yêu cầu chờ BO duyệt. Vui lòng đợi xử lý trước khi gửi thay đổi mới.");
        }
        ManagerUnitTypeRequest before = technicalSnapshot(type);
        ManagerUnitTypeRequest after = normalizedChange(request);
        long currentCount = units.countByFacility_IdAndUnitType_Id(facilityId, unitTypeId);
        int requestedCount = request.physicalUnitCount();
        boolean quantityChanged = requestedCount > 0 && requestedCount != currentCount;

        if (quantityChanged) {
            if (requestedCount > currentCount) {
                int toAdd = (int) (requestedCount - currentCount);
                createPhysicalUnits(type, toAdd, request.floor(), request.zone());
            } else {
                int toRemove = (int) (currentCount - requestedCount);
                var availableUnits = units.findByFacility_Id(facilityId).stream()
                    .filter(u -> u.getUnitType().getId().equals(unitTypeId))
                    .filter(u -> u.getStatus() == StorageUnitStatus.available)
                    .sorted((a, b) -> b.getCode().compareTo(a.getCode()))
                    .toList();
                if (availableUnits.size() < toRemove) {
                    long inUse = currentCount - availableUnits.size();
                    throw ApiExceptions.conflict("Không thể giảm xuống " + requestedCount + " gian kho vì hiện có " + inUse + " gian đang được thuê hoặc giữ chỗ.");
                }
                var unitsToDelete = availableUnits.subList(0, toRemove);
                units.deleteAll(unitsToDelete);
                units.flush();
            }
        }

        boolean metadataChanged = !java.util.Objects.equals(type.getName(), after.name())
            || !java.util.Objects.equals(type.getDescription(), after.description())
            || !java.util.Objects.equals(type.getImageUrl(), after.imageUrl())
            || !java.util.Objects.equals(type.getDetailImageUrl(), after.detailImageUrl());

        if (!requiresRepricing && !metadataChanged && !quantityChanged) {
            throw ApiExceptions.conflict("Thông tin loại kho chưa có thay đổi.");
        }

        if (requiresRepricing || metadataChanged) {
            var change = new com.storagehub.domain.model.UnitTypeChangeRequest();
            change.setUnitTypeId(unitTypeId);
            change.setFacilityId(facilityId);
            change.setFacilityName(type.getFacility().getName());
            change.setUnitTypeCode(type.getCode());
            change.setRequestedBy(actor.userId());
            change.setRequesterName(users.findById(actor.userId()).map(com.storagehub.domain.model.User::getFullName).orElse("Manager"));
            change.setBeforeJson(json(before));
            change.setAfterJson(json(after));
            change.setRequiresRepricing(requiresRepricing);
            change = changes.saveAndFlush(change);
            audit.recordMutation("UNIT_TYPE_CHANGE_REQUESTED", "UnitTypeChangeRequest", change.getId(), facilityId, before, after);
            notifications.notifyBusinessOfUnitTypeReview(type, change.getId());
        } else if (quantityChanged) {
            audit.recordMutation("UNIT_TYPE_QUANTITY_UPDATED", "UnitType", type.getId(), facilityId, before, unitTypeResponse(type));
        }
        return unitTypeResponse(type);
    }

    private ManagerUnitTypeRequest technicalSnapshot(UnitType t) {
        return new ManagerUnitTypeRequest(t.getCode(), t.getName(), t.getDescription(), t.getLengthM(), t.getWidthM(),
            t.getHeightM(), t.getMaxLoadKg(), t.getRackCount(), t.getRackLengthM(), t.getRackWidthM(), t.getRackHeightM(),
            t.getAisleWidthM(), t.getTrolleyType(), t.getSmallBoxCapacity() == null ? 0 : t.getSmallBoxCapacity(),
            t.getLargeBoxCapacity() == null ? 0 : t.getLargeBoxCapacity(), t.getImageUrl(), 0, null, null, t.getDetailImageUrl(),
            goodsScope(t).categoryIds(), goodsScope(t).materialIds());
    }

    private ManagerUnitTypeRequest normalizedChange(ManagerUnitTypeRequest r) {
        return new ManagerUnitTypeRequest(clean(r.code()), r.name().trim(), clean(r.description()), r.lengthM(),
            r.widthM(), r.heightM(), r.maxLoadKg(), r.rackCount(), r.rackLengthM(), r.rackWidthM(), r.rackHeightM(),
            r.aisleWidthM(), r.trolleyType().trim(), r.smallBoxCapacity(), r.largeBoxCapacity(), clean(r.imageUrl()), 0, null, null, clean(r.detailImageUrl()),
            distinct(r.acceptedGoodsCategoryIds()), distinct(r.acceptedGoodsMaterialIds()));
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Cannot serialize review", e); }
    }

    private ManagerUnitTypeRequest readChange(String value) {
        try { return objectMapper.readValue(value, ManagerUnitTypeRequest.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Cannot read review", e); }
    }

    private com.storagehub.api.facility.UnitTypeChangeResponse changeResponse(com.storagehub.domain.model.UnitTypeChangeRequest r) {
        UnitType current = unitTypes.findById(r.getUnitTypeId()).orElse(null);
        return new com.storagehub.api.facility.UnitTypeChangeResponse(r.getId(), r.getUnitTypeId(), r.getFacilityId(),
            r.getFacilityName(), r.getUnitTypeCode(), r.getRequestedBy(), r.getRequesterName(), r.getCreatedAt(), r.getStatus(),
            readChange(r.getBeforeJson()), readChange(r.getAfterJson()), r.getReviewedBy(), r.getReviewedAt(), r.getRejectionReason(), r.isRequiresRepricing(),
            current == null ? null : current.getMonthlyPrice(), current == null ? null : current.getSecurityDepositAmount());
    }

    @Transactional(readOnly = true)
    public List<com.storagehub.api.facility.UnitTypeChangeResponse> listUnitTypeChanges(ActorPrincipal actor) {
        requireBusiness(actor);
        return changes.findTop100ByOrderByCreatedAtDesc().stream().map(this::changeResponse).toList();
    }

    @Transactional(readOnly = true)
    public com.storagehub.api.facility.UnitTypeChangeResponse getUnitTypeChange(ActorPrincipal actor, UUID id) {
        requireBusiness(actor);
        return changeResponse(changes.findById(id).orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy yêu cầu duyệt.")));
    }

    @Transactional
    public com.storagehub.api.facility.UnitTypeChangeResponse reviewUnitTypeChange(ActorPrincipal actor, UUID id, boolean approved, String reason,
                                                                                  BigDecimal monthlyPrice, BigDecimal securityDepositAmount) {
        requireBusiness(actor);
        var change = changes.findLockedById(id).orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy yêu cầu duyệt."));
        if (!"PENDING".equals(change.getStatus())) throw ApiExceptions.conflict("Yêu cầu này đã được xử lý.");
        if (!approved && clean(reason) == null) throw ApiExceptions.conflict("Vui lòng nhập lý do từ chối.");
        UnitType type = unitTypes.findByIdForUpdate(change.getUnitTypeId())
            .orElseThrow(() -> ApiExceptions.notFound("Không tìm thấy loại kho."));
        if (approved) {
            ManagerUnitTypeRequest after = readChange(change.getAfterJson());
            if (!json(technicalSnapshot(type)).equals(json(readChange(change.getBeforeJson())))) {
                throw ApiExceptions.conflict("Thông tin gốc đã thay đổi. Hãy từ chối và yêu cầu Manager gửi lại.");
            }
            UnitTypeResponse before = unitTypeResponse(type);
            if (change.isRequiresRepricing() && (monthlyPrice == null || monthlyPrice.signum() <= 0
                || securityDepositAmount == null || securityDepositAmount.signum() < 0)) {
                throw ApiExceptions.validation("Thay đổi này ảnh hưởng giá. BO phải xác nhận giá thuê và tiền đảm bảo trước khi duyệt.", null);
            }
            applyTechnical(type, after);
            if (monthlyPrice != null) type.setMonthlyPrice(monthlyPrice);
            if (securityDepositAmount != null) type.setSecurityDepositAmount(securityDepositAmount);
            type.setPublishedRevision(Math.max(1, type.getPublishedRevision() + 1));
            unitTypes.save(type);
            audit.recordMutation("UNIT_TYPE_CHANGE_APPROVED", "UnitType", type.getId(), change.getFacilityId(), before, unitTypeResponse(type));
        }
        change.setStatus(approved ? "APPROVED" : "REJECTED");
        change.setReviewedBy(actor.userId());
        change.setReviewedAt(java.time.Instant.now());
        change.setRejectionReason(approved ? null : clean(reason));
        changes.save(change);
        audit.recordMutation("UNIT_TYPE_CHANGE_REVIEWED", "UnitTypeChangeRequest", id, change.getFacilityId(), null, changeResponse(change));
        notifications.notifyUnitTypeReviewResult(change);
        return changeResponse(change);
    }

    /** Compatibility overload retained for existing callers and tests. */
    public com.storagehub.api.facility.UnitTypeChangeResponse reviewUnitTypeChange(ActorPrincipal actor, UUID id, boolean approved, String reason) {
        return reviewUnitTypeChange(actor, id, approved, reason, null, null);
    }

    private boolean different(BigDecimal before, BigDecimal after) {
        return before == null || after == null ? before != after : before.compareTo(after) != 0;
    }

    private boolean technicalChanged(UnitType t, ManagerUnitTypeRequest r) {
        return different(t.getLengthM(), r.lengthM()) || different(t.getWidthM(), r.widthM())
            || different(t.getHeightM(), r.heightM()) || different(t.getMaxLoadKg(), r.maxLoadKg())
            || t.getRackCount() != r.rackCount() || different(t.getRackLengthM(), r.rackLengthM())
            || different(t.getRackWidthM(), r.rackWidthM()) || different(t.getRackHeightM(), r.rackHeightM())
            || different(t.getAisleWidthM(), r.aisleWidthM())
            || !java.util.Objects.equals(t.getTrolleyType(), r.trolleyType().trim())
            || !java.util.Objects.equals(t.getSmallBoxCapacity(), r.smallBoxCapacity())
            || !java.util.Objects.equals(t.getLargeBoxCapacity(), r.largeBoxCapacity());
    }

    private boolean goodsScopeChanged(UnitType type, ManagerUnitTypeRequest request) {
        GoodsScope current = goodsScope(type);
        return !current.categoryIds().equals(distinct(request.acceptedGoodsCategoryIds()))
            || !current.materialIds().equals(distinct(request.acceptedGoodsMaterialIds()));
    }

    private List<UUID> distinct(List<UUID> values) {
        return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
    }

    private record GoodsScope(List<UUID> categoryIds, List<UUID> materialIds) {}

    private GoodsScope goodsScope(UnitType type) {
        try {
            return objectMapper.readValue(type.getAcceptedGoodsJson(), GoodsScope.class);
        } catch (Exception ignored) {
            return new GoodsScope(List.of(), List.of());
        }
    }

    private String approvedGoodsJson(ManagerUnitTypeRequest request) {
        List<UUID> categoryIds = distinct(request.acceptedGoodsCategoryIds());
        List<UUID> materialIds = distinct(request.acceptedGoodsMaterialIds());
        // Backward-compatible requests created before scoped goods existed mean "all active".
        if (categoryIds.isEmpty()) categoryIds = goodsTypes.findAllByStatusOrderByCodeAsc(com.storagehub.domain.model.GoodsCatalogStatus.ACTIVE)
            .stream().map(com.storagehub.domain.model.GoodsTypeCatalog::getId).sorted().toList();
        List<UUID> selectedCategoryIds = categoryIds;
        if (materialIds.isEmpty()) materialIds = goodsMaterials.findAll().stream()
            .filter(value -> value.getStatus() == com.storagehub.domain.model.GoodsCatalogStatus.ACTIVE)
            .filter(value -> selectedCategoryIds.contains(value.getGoodsType().getId()))
            .map(com.storagehub.domain.model.GoodsMaterialCatalog::getId).sorted().toList();
        var categories = goodsTypes.findAllById(categoryIds);
        var materials = goodsMaterials.findAllById(materialIds);
        if (categories.size() != categoryIds.size() || categories.stream().anyMatch(v -> v.getStatus() != com.storagehub.domain.model.GoodsCatalogStatus.ACTIVE)) {
            throw ApiExceptions.validation("Có loại hàng không tồn tại hoặc đã ngừng áp dụng.", null);
        }
        if (materials.size() != materialIds.size() || materials.stream().anyMatch(v -> v.getStatus() != com.storagehub.domain.model.GoodsCatalogStatus.ACTIVE)) {
            throw ApiExceptions.validation("Có vật liệu không tồn tại hoặc đã ngừng áp dụng.", null);
        }
        if (materials.stream().anyMatch(v -> !selectedCategoryIds.contains(v.getGoodsType().getId()))) {
            throw ApiExceptions.validation("Vật liệu được chọn phải thuộc một loại hàng được áp dụng.", null);
        }
        return json(new GoodsScope(categoryIds, materialIds));
    }

    @Transactional
    public void deleteUnitType(ActorPrincipal actor, UUID unitTypeId) {
        UnitType type = unitTypes.findByIdForUpdate(unitTypeId)
            .orElseThrow(() -> ApiExceptions.notFound("Unit type was not found"));
        UUID facilityId = type.getFacility().getId();
        requireManager(actor, facilityId);
        if (changes.existsByUnitTypeIdAndStatus(unitTypeId, "PENDING")) {
            throw ApiExceptions.conflict("Không thể xóa loại kho đang chờ BO duyệt thay đổi.");
        }
        UnitTypeResponse before = unitTypeResponse(type);
        type.setStatus(UnitTypeStatus.archived);
        UnitType saved = unitTypes.saveAndFlush(type);
        audit.recordMutation("UNIT_TYPE_ARCHIVED_BY_MANAGER", "UnitType", unitTypeId, facilityId,
            before, unitTypeResponse(saved));
    }

    @Transactional
    public UnitTypeResponse priceUnitType(ActorPrincipal actor, UUID unitTypeId, UnitTypePricingRequest request) {
        requireBusiness(actor);
        UnitType type = unitTypes.findById(unitTypeId)
            .orElseThrow(() -> ApiExceptions.notFound("Unit type was not found"));
        UnitTypeResponse before = unitTypeResponse(type);
        type.setMonthlyPrice(request.monthlyPrice());
        type.setSecurityDepositAmount(request.securityDepositAmount());
        if (type.getStatus() == UnitTypeStatus.draft) type.setStatus(UnitTypeStatus.awaiting_pricing);
        UnitType saved = unitTypes.save(type);
        UnitTypeResponse after = unitTypeResponse(saved);
        audit.recordMutation("UNIT_TYPE_PRICED_BY_BUSINESS", "UnitType", saved.getId(), saved.getFacility().getId(), before, after);
        return after;
    }

    @Transactional
    public FacilityResponse publish(ActorPrincipal actor, UUID facilityId) {
        requireBusiness(actor);
        Facility facility = facility(facilityId);
        validateAndActivate(facility);
        Facility saved = facilities.save(facility);
        FacilityResponse after = facilityResponse(saved);
        audit.recordMutation("FACILITY_PUBLISHED", "Facility", facilityId, facilityId, null, after);
        return after;
    }

    private void validateAndActivate(Facility facility) {
        UUID facilityId = facility.getId();
        List<UnitType> candidates = unitTypes.findByFacility_IdOrderByCodeAsc(facilityId).stream()
            .filter(type -> type.getStatus() == UnitTypeStatus.awaiting_pricing || type.getStatus() == UnitTypeStatus.active)
            .toList();
        if (candidates.isEmpty()) throw ApiExceptions.conflict("Chưa thể công bố: Manager cần khai báo ít nhất một loại kho thực tế.");
        for (UnitType type : candidates) {
            if (type.getMonthlyPrice() == null || type.getMonthlyPrice().signum() <= 0) {
                throw ApiExceptions.conflict("Chưa thể công bố: loại kho " + type.getCode() + " chưa được BO cấu hình giá thuê lớn hơn 0.");
            }
            if (units.countByFacility_IdAndUnitType_IdAndStatus(facilityId, type.getId(), StorageUnitStatus.available) <= 0) {
                throw ApiExceptions.conflict("Chưa thể công bố: loại kho " + type.getCode() + " chưa có gian kho sẵn sàng.");
            }
        }
        boolean hasEffectivePackage = packages
            .findAllByFacility_IdAndActiveTrueAndEffectiveFromLessThanEqualOrderByRentalMonthsAsc(facilityId, LocalDate.now())
            .stream().anyMatch(p -> p.getEffectiveTo() == null || !p.getEffectiveTo().isBefore(LocalDate.now()));
        if (!hasEffectivePackage) throw ApiExceptions.conflict("Chưa thể công bố: cơ sở chưa được gán gói thuê đang áp dụng và còn hiệu lực. Vào Gói thuê để thêm cơ sở vào gói thuê.");

        candidates.forEach(type -> type.setStatus(UnitTypeStatus.active));
        candidates.forEach(type -> { if (type.getPublishedRevision() == 0) type.setPublishedRevision(1); });
        unitTypes.saveAll(candidates);
        facility.setStatus(FacilityStatus.active);
    }

    private void applyTechnical(UnitType type, ManagerUnitTypeRequest request) {
        type.setName(request.name().trim());
        type.setDescription(clean(request.description()));
        type.setLengthM(request.lengthM());
        type.setWidthM(request.widthM());
        type.setHeightM(request.heightM());
        type.setMaxLoadKg(request.maxLoadKg());
        type.setRackCount(request.rackCount());
        type.setRackLengthM(request.rackLengthM());
        type.setRackWidthM(request.rackWidthM());
        type.setRackHeightM(request.rackHeightM());
        type.setAisleWidthM(request.aisleWidthM());
        type.setTrolleyType(request.trolleyType().trim());
        type.setSmallBoxCapacity(request.smallBoxCapacity());
        type.setLargeBoxCapacity(request.largeBoxCapacity());
        type.setAcceptedGoodsJson(approvedGoodsJson(request));
        type.setImageUrl(request.imageUrl() == null || request.imageUrl().isBlank() ? null : request.imageUrl().trim());
        type.setDetailImageUrl(clean(request.detailImageUrl()));
    }

    private String extractZonePrefix(String zone) {
        if (zone == null || zone.isBlank()) return "A";
        String clean = java.text.Normalizer.normalize(zone.trim(), java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .replace('đ', 'd')
            .replace('Đ', 'D');
        String stripped = clean.replaceAll("(?i)^(khu\\s*vuc|khu|zone)\\s*", "").trim();
        if (!stripped.isEmpty()) {
            String alphanumeric = stripped.replaceAll("[^a-zA-Z0-9]", "");
            if (!alphanumeric.isEmpty()) {
                return alphanumeric.toUpperCase();
            }
        }
        return clean.substring(0, Math.min(2, clean.length())).toUpperCase();
    }

    private String extractFloorNumber(String floor) {
        if (floor == null || floor.isBlank()) return "1";
        String digits = floor.replaceAll("[^0-9]", "").trim();
        if (!digits.isEmpty()) return digits;
        return floor.trim().toUpperCase();
    }

    private void createPhysicalUnits(UnitType type, int count, String floor, String zone) {
        String zonePrefix = extractZonePrefix(zone);
        String floorNum = extractFloorNumber(floor);
        int createdCount = 0;
        int index = 1;
        while (createdCount < count) {
            String candidateCode = "%s-%s%02d".formatted(zonePrefix, floorNum, index);
            if (!units.existsByCode(candidateCode)) {
                StorageUnit unit = new StorageUnit();
                unit.setFacility(type.getFacility());
                unit.setUnitType(type);
                unit.setCode(candidateCode);
                unit.setFloor(floor);
                unit.setZone(zone);
                unit.setStatus(StorageUnitStatus.available);
                units.save(unit);
                createdCount++;
            }
            index++;
            if (index > 999) break;
        }
    }

    private String generateUnitTypeCode(UUID facilityId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = "UT-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10).toUpperCase();
            if (unitTypes.findByFacility_IdAndCode(facilityId, code).isEmpty()) return code;
        }
        throw ApiExceptions.conflict("Không thể sinh mã loại kho duy nhất. Vui lòng thử lại.");
    }

    private void requireBusiness(ActorPrincipal actor) {
        if (actor == null || !actor.hasRole(RoleCode.BUSINESS)) {
            throw ApiExceptions.forbidden("Only Business Operations may perform this action");
        }
        authorization.require(actor, SystemPermission.MANAGE_POLICIES);
    }

    private void requireManager(ActorPrincipal actor, UUID facilityId) {
        if (actor == null || !actor.hasRole(RoleCode.MANAGER)) {
            throw ApiExceptions.forbidden("Only a Facility Manager may define unit types");
        }
        authorization.require(actor, SystemPermission.MANAGE_INVENTORY);
        scopes.assertCanManage(actor, facilityId);
    }

    private Facility facility(UUID id) {
        return facilities.findById(id).orElseThrow(() -> ApiExceptions.notFound("Facility was not found"));
    }

    private FacilityResponse facilityResponse(Facility f) {
        return new FacilityResponse(f.getId(), f.getCode(), f.getName(), f.getAddress(), f.getCity(),
            f.getPhone(), f.getAccessHours(), f.getImageUrl(), f.isClimateControlled(), f.getSecurityDescription(),
            managerNames(f.getId()),
            f.getStatus(), f.getCreatedAt(), f.getUpdatedAt());
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<String> managerNames(UUID facilityId) {
        return userFacilityScopes.findByFacility_Id(facilityId).stream()
            .filter(scope -> scope.getUser().getStatus() == com.storagehub.domain.model.UserStatus.ACTIVE)
            .filter(scope -> scope.getUser().getRoles().stream().anyMatch(role -> role.getCode() == RoleCode.MANAGER))
            .map(scope -> scope.getUser().getFullName()).sorted().toList();
    }

    private UnitTypeResponse unitTypeResponse(UnitType t) {
        long available = units.countByFacility_IdAndUnitType_IdAndStatus(t.getFacility().getId(), t.getId(), StorageUnitStatus.available);
        return new UnitTypeResponse(t.getId(), t.getFacility().getId(), t.getCode(), t.getName(), t.getDescription(), t.getLengthM(), t.getWidthM(), t.getHeightM(),
            t.getAreaM2(), t.getVolumeM3(), t.getMonthlyPrice(), t.getSecurityDepositAmount(), t.getImageUrl(), t.getMaxLoadKg(),
            t.getRackCount(), t.getRackLengthM(), t.getRackWidthM(), t.getRackHeightM(), t.getAisleWidthM(), t.getTrolleyType(),
            t.getSmallBoxCapacity(), t.getLargeBoxCapacity(), t.getStatus(),
            units.countByFacility_IdAndUnitType_Id(t.getFacility().getId(), t.getId()), available,
            units.countByFacility_IdAndUnitType_IdAndStatus(t.getFacility().getId(), t.getId(), StorageUnitStatus.occupied), t.getCreatedAt(), t.getUpdatedAt(), t.getDetailImageUrl(),
            t.getPublishedRevision(), goodsScope(t).categoryIds(), goodsScope(t).materialIds());
    }
}
