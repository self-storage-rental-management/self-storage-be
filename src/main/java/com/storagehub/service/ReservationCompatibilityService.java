package com.storagehub.service;

import com.storagehub.api.reservation.CompatibilityCheckRequest;
import com.storagehub.api.reservation.CompatibilityCheckResponse;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.api.reservation.ReservationRecommendationRequest;
import com.storagehub.api.reservation.ReservationRecommendationResponse;
import com.storagehub.api.reservation.ReservationRecommendationStatus;
import com.storagehub.api.reservation.ReservationPackagePricing;
import com.storagehub.api.reservation.ReservationStorageLimits;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.CompatibilityResult;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.RentalPackagePolicy;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.RentalPackagePolicyRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationCompatibilityService {

    private static final BigDecimal CUBIC_CENTIMETERS_PER_CUBIC_METER = new BigDecimal("1000000");

    private final FacilityRepository facilityRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final RentalPackagePolicyRepository policyRepository;
    private final ReservationCapacityService capacityService;
    private final GoodsCatalogService goodsCatalogService;
    private final PalletInventoryService palletInventory;

    @Autowired
    public ReservationCompatibilityService(
        FacilityRepository facilityRepository,
        UnitTypeRepository unitTypeRepository,
        RentalPackagePolicyRepository policyRepository,
        ReservationCapacityService capacityService,
        GoodsCatalogService goodsCatalogService,
        PalletInventoryService palletInventory
    ) {
        this.facilityRepository = facilityRepository;
        this.unitTypeRepository = unitTypeRepository;
        this.policyRepository = policyRepository;
        this.capacityService = capacityService;
        this.goodsCatalogService = goodsCatalogService;
        this.palletInventory = palletInventory;
    }

    @Transactional(readOnly = true)
    public CompatibilityCheckResponse check(ActorPrincipal actor, CompatibilityCheckRequest request) {
        return check(actor, request, true, false);
    }

    @Transactional
    public CompatibilityCheckResponse checkQuotedSnapshot(ActorPrincipal actor, CompatibilityCheckRequest request) {
        return check(actor, request, false, true);
    }

    @Transactional(readOnly = true)
    public ReservationRecommendationResponse recommend(
        ActorPrincipal actor,
        ReservationRecommendationRequest request
    ) {
        if (!actor.hasRole(RoleCode.CUSTOMER)) {
            throw ApiExceptions.forbidden("Only customers can request reservation recommendations");
        }
        validateDates(request.getStartDate(), request.getEndDate());

        Facility facility = facilityRepository.findById(request.getFacilityId())
            .orElseThrow(() -> ApiExceptions.notFound("Facility was not found"));
        if (facility.getStatus() != FacilityStatus.active) {
            throw ApiExceptions.conflict("Facility is not available for reservations");
        }
        RentalPackagePolicy policy = policyRepository == null ? null : policyRepository
            .findByFacility_IdAndCode(facility.getId(), request.getPricingPackageCode().trim())
            .filter(candidate -> candidate.isActive())
            .filter(candidate -> !candidate.getEffectiveFrom().isAfter(request.getStartDate()))
            .filter(candidate -> candidate.getEffectiveTo() == null
                || !candidate.getEffectiveTo().isBefore(request.getEndDate()))
            .orElseThrow(() -> ApiExceptions.conflict("Rental package is not effective for the selected facility and date"));
        int rentalMonths = ReservationQuoteService.calculateRentalMonths(
            request.getStartDate(), request.getEndDate()
        );
        if (policy != null && policy.getRentalMonths() != rentalMonths) {
            throw ApiExceptions.validation("Rental period does not match the selected package", null);
        }

        List<UnitType> candidates = unitTypeRepository
            .findByFacility_IdOrderByCodeAsc(facility.getId())
            .stream()
            .filter(unitType -> unitType.getStatus() == UnitTypeStatus.active)
            .toList();
        List<UUID> evaluatedIds = candidates.stream().map(UnitType::getId).toList();
        List<UUID> incompatibleIds = new ArrayList<>();
        List<UUID> physicallyFitButUnavailableIds = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        List<RecommendationCandidate> compatibleCandidates = new ArrayList<>();
        Map<UUID, CompatibilityCheckResponse> checks = new LinkedHashMap<>();
        Map<UUID, ReservationPackagePricing> pricing = new LinkedHashMap<>();
        ReservationStorageLimits maxSupportedStorageLimits = null;

        for (UnitType candidate : candidates) {
            if (policy != null && candidate.getMonthlyPrice() != null && candidate.getMonthlyPrice().signum() > 0) {
                pricing.put(candidate.getId(), ReservationQuoteService.priceForPackage(candidate, policy, rentalMonths));
            }
            CompatibilityCheckResponse result;
            try {
                result = check(actor, new CompatibilityCheckRequest(
                    facility.getId(), candidate.getId(), request.getStartDate(), request.getEndDate(),
                    request.getGoodsCondition(), copyItems(request.getGoodsItems())
                ));
            } catch (ApiException exception) {
                incompatibleIds.add(candidate.getId());
                addIssue(issues, exception.getMessage());
                checks.put(candidate.getId(), new CompatibilityCheckResponse(facility.getId(), candidate.getId(),
                    request.getStartDate(), request.getEndDate(), CompatibilityResult.INCOMPATIBLE, null, null,
                    candidate.getVolumeM3(), candidate.getMaxLoadKg(), null, null, 0, 0, 0, false, List.of(exception.getMessage())));
                continue;
            }

            checks.put(candidate.getId(), result);
            maxSupportedStorageLimits = maxLimits(maxSupportedStorageLimits, result);
            boolean physicallyFits = "FIT".equals(result.getPhysicalFitStatus())
                || (result.getPhysicalFitStatus() == null
                    && result.getPalletPlan() != null
                    && result.getResult() != CompatibilityResult.INCOMPATIBLE);
            if (!physicallyFits) {
                incompatibleIds.add(candidate.getId());
                result.getIssues().forEach(issue -> addIssue(issues, issue));
                continue;
            }
            if (result.getAvailableUnitCount() <= 0) {
                physicallyFitButUnavailableIds.add(candidate.getId());
                result.getIssues().forEach(issue -> addIssue(issues, issue));
                continue;
            }
            compatibleCandidates.add(new RecommendationCandidate(candidate, result));
        }

        compatibleCandidates.sort(Comparator
            .comparingInt((RecommendationCandidate item) -> recommendationRank(item.result().getResult()))
            .thenComparing(item -> item.unitType().getVolumeM3())
            .thenComparing(item -> item.unitType().getCode()));

        RecommendationCandidate selected = compatibleCandidates.isEmpty() ? null : compatibleCandidates.get(0);
        if (selected == null && candidates.isEmpty()) {
            addIssue(issues, "No active unit type is available at the selected facility");
        }
        if (selected == null && issues.isEmpty()) {
            addIssue(issues, "No unit type is compatible with the declared goods and rental period");
        }

        CompatibilityCheckResponse selectedResult = selected == null ? null : selected.result();
        ReservationRecommendationStatus status = selected != null
            ? ReservationRecommendationStatus.ALTERNATIVE_AVAILABLE
            : physicallyFitButUnavailableIds.isEmpty()
                ? ReservationRecommendationStatus.NO_COMPATIBLE_WAREHOUSE
                : ReservationRecommendationStatus.NO_VACANT_WAREHOUSE;
        String noAlternativeReason = selected == null
            ? physicallyFitButUnavailableIds.isEmpty()
                ? "Không có loại kho nào tại cơ sở này phù hợp với toàn bộ hàng hóa. Chưa có loại kho nào vừa toàn bộ hàng và còn gian trống trong kỳ thuê đã chọn."
                : "Có loại kho phù hợp với toàn bộ hàng hóa nhưng hiện không còn gian trống trong kỳ thuê đã chọn."
            : null;
        return new ReservationRecommendationResponse(
            facility.getId(),
            selected == null ? null : selected.unitType().getId(),
            policy == null ? request.getPricingPackageCode() : policy.getCode(),
            policy == null ? null : policy.getPolicyVersion(),
            rentalMonths,
            selectedResult == null ? CompatibilityResult.INCOMPATIBLE : selectedResult.getResult(),
            selectedResult == null ? null : selectedResult.getTotalGoodsVolumeM3(),
            selectedResult == null ? null : selectedResult.getTotalGoodsWeightKg(),
            selectedResult == null ? 0 : selectedResult.getAvailableUnitCount(),
            evaluatedIds,
            List.copyOf(incompatibleIds),
            List.copyOf(issues),
            Map.copyOf(checks),
            Map.copyOf(pricing),
            noAlternativeReason,
            status,
            List.copyOf(physicallyFitButUnavailableIds),
            maxSupportedStorageLimits
        );
    }

    private ReservationStorageLimits maxLimits(ReservationStorageLimits current,
                                               CompatibilityCheckResponse check) {
        if (check.getMaxCargoLengthCm() == null || check.getMaxCargoWidthCm() == null
            || check.getMaxCargoHeightCm() == null || check.getMaxCargoWeightKg() == null) {
            return current;
        }
        if (current == null) {
            return new ReservationStorageLimits(
                check.getMaxCargoLengthCm(), check.getMaxCargoWidthCm(),
                check.getMaxCargoHeightCm(), check.getMaxCargoWeightKg()
            );
        }
        return new ReservationStorageLimits(
            current.maxCargoLengthCm().max(check.getMaxCargoLengthCm()),
            current.maxCargoWidthCm().max(check.getMaxCargoWidthCm()),
            current.maxCargoHeightCm().max(check.getMaxCargoHeightCm()),
            current.maxCargoWeightKg().max(check.getMaxCargoWeightKg())
        );
    }

    private int recommendationRank(CompatibilityResult result) {
        return result == CompatibilityResult.COMPATIBLE ? 0 : 1;
    }

    private void addIssue(List<String> issues, String issue) {
        if (issue != null && !issue.isBlank() && !issues.contains(issue)) issues.add(issue);
    }

    private List<GoodsItemRequest> copyItems(List<GoodsItemRequest> items) {
        return items.stream().map(item -> {
            GoodsItemRequest copy = new GoodsItemRequest(
                item.getCategory(), item.getCustomGoodsName(), item.getMaterialName(), item.getCustomMaterial(),
                item.getDescription(), item.getCustomerNote(), item.getQuantity(), item.getLengthCm(),
                item.getWidthCm(), item.getHeightCm(), item.getWeightPerItemKg(), item.isFragile()
            );
            copy.setCatalogCategoryId(item.getCatalogCategoryId());
            copy.setCatalogMaterialId(item.getCatalogMaterialId());
            copy.setCategoryCode(item.getCategoryCode());
            copy.setCategoryName(item.getCategoryName());
            copy.setMaterialCode(item.getMaterialCode());
            copy.setStackable(item.getStackable());
            copy.setMaxStackLayers(item.getMaxStackLayers());
            copy.setAllowHorizontalPlacement(item.getAllowHorizontalPlacement());
            return copy;
        }).toList();
    }

    private record RecommendationCandidate(UnitType unitType, CompatibilityCheckResponse result) {}

    private CompatibilityCheckResponse check(ActorPrincipal actor, CompatibilityCheckRequest request,
                                             boolean validateCurrentCatalog, boolean lockUnit) {
        if (!actor.hasRole(RoleCode.CUSTOMER)) {
            throw ApiExceptions.forbidden("Only customers can check reservation compatibility");
        }
        validateDates(request.getStartDate(), request.getEndDate());

        Facility facility = facilityRepository.findById(request.getFacilityId())
            .orElseThrow(() -> ApiExceptions.notFound("Facility was not found"));
        if (facility.getStatus() != FacilityStatus.active) {
            throw ApiExceptions.conflict("Facility is not available for reservations");
        }

        UnitType unitType = unitTypeRepository.findById(request.getUnitTypeId())
            .orElseThrow(() -> ApiExceptions.notFound("Unit type was not found"));
        if (!unitType.getFacility().getId().equals(facility.getId())) {
            throw ApiExceptions.validation("Unit type does not belong to the selected facility", null);
        }
        if (unitType.getStatus() != UnitTypeStatus.active) {
            throw ApiExceptions.conflict("Unit type is not available for reservations");
        }

        long availableCount = capacityService.availableCount(
            facility.getId(), unitType.getId(), request.getStartDate(), request.getEndDate()
        );
        BigDecimal totalVolume = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        boolean reviewRequired = false;

        for (int index = 0; index < request.getGoodsItems().size(); index++) {
            GoodsItemRequest item = request.getGoodsItems().get(index);
            if (validateCurrentCatalog && goodsCatalogService != null) {
                goodsCatalogService.validateForUnitType(unitType, item, index);
            }
            validateOtherItem(item, index, validateCurrentCatalog);
            BigDecimal quantity = BigDecimal.valueOf(item.getQuantity());
            BigDecimal itemVolume = item.getLengthCm()
                .multiply(item.getWidthCm())
                .multiply(item.getHeightCm())
                .multiply(quantity)
                .divide(CUBIC_CENTIMETERS_PER_CUBIC_METER);
            totalVolume = totalVolume.add(itemVolume);
            totalWeight = totalWeight.add(item.getWeightPerItemKg().multiply(quantity));
            reviewRequired = reviewRequired || isOther(item);
        }

        var fit = palletInventory.check(
            unitType,
            request.getGoodsItems(),
            availableCount,
            request.getStartDate(),
            request.getEndDate(),
            lockUnit
        );
        boolean layoutReviewRequired = fit.reviewRequired();
        CompatibilityResult result = layoutReviewRequired
            ? CompatibilityResult.REVIEW_REQUIRED
            : fit.plan() == null
                ? CompatibilityResult.INCOMPATIBLE
                : reviewRequired ? CompatibilityResult.REVIEW_REQUIRED : CompatibilityResult.COMPATIBLE;

        var response = new CompatibilityCheckResponse(
            facility.getId(), unitType.getId(), request.getStartDate(), request.getEndDate(), result,
            totalVolume.setScale(6, RoundingMode.HALF_UP),
            totalWeight.setScale(2, RoundingMode.HALF_UP),
            unitType.getVolumeM3().setScale(6, RoundingMode.HALF_UP),
            palletInventory.cargoLimitKg(unitType).setScale(2, RoundingMode.HALF_UP),
            null, null, fit.plan() == null ? 0 : fit.plan().racks().size(), 0,
            fit.fittingUnits(), reviewRequired || layoutReviewRequired, fit.issues()
        );
        response.setPalletPlan(fit.plan());
        response.setRequiredPallets(fit.requiredPallets());
        response.setAvailablePalletSlots(fit.availableSlots());
        response.setPalletLayoutMissing(fit.layoutMissing());
        response.setPalletHeldUnit(fit.heldUnit());
        PalletInventoryService.StorageLimits storageLimits = palletInventory.storageLimits(unitType);
        if (storageLimits != null) {
            response.setMaxCargoLengthCm(storageLimits.maxCargoLengthCm());
            response.setMaxCargoWidthCm(storageLimits.maxCargoWidthCm());
            response.setMaxCargoHeightCm(storageLimits.maxCargoHeightCm());
            response.setMaxCargoWeightKg(storageLimits.maxCargoWeightKg());
        }
        response.setPhysicalFitStatus(fit.layoutMissing() || layoutReviewRequired
            ? "REVIEW_REQUIRED" : fit.plan() == null ? "INCOMPATIBLE" : "FIT");
        response.setAvailabilityStatus(fit.layoutMissing() || layoutReviewRequired
            ? "NOT_EVALUATED" : fit.plan() == null
            ? "NOT_EVALUATED" : fit.fittingUnits() > 0 ? "AVAILABLE" : "NO_VACANT_WAREHOUSE");
        return response;
    }

    private void validateDates(LocalDate startDate, LocalDate endDate) {
        if (startDate.isBefore(LocalDate.now())) {
            throw ApiExceptions.validation("startDate cannot be in the past", null);
        }
        if (!endDate.isAfter(startDate)) {
            throw ApiExceptions.validation("endDate must be after startDate", null);
        }
    }

    static boolean isOther(GoodsItemRequest item) {
        return item.getCategoryCode() == null ? item.getCategory() == GoodsCategory.OTHER
            : "OTHER".equals(item.getCategoryCode());
    }

    private void validateOtherItem(GoodsItemRequest item, int index, boolean validateCurrentCatalog) {
        if (isOther(item) && isBlank(item.getCustomGoodsName())) {
            throw ApiExceptions.validation(
                "goodsItems[" + index + "] requires customGoodsName for OTHER category", null
            );
        }
        if (!isOther(item) && !hasOptionalMaterial(item) && isBlank(item.getMaterialName())) {
            throw ApiExceptions.validation(
                "goodsItems[" + index + "] requires materialName for a standard category", null
            );
        }
        // The database-backed catalog and per-unit-type scope are validated together by the caller.
    }

    private static boolean hasOptionalMaterial(GoodsItemRequest item) {
        String code = item.getCategoryCode() == null ? item.getCategory().name() : item.getCategoryCode();
        return "ELECTRONICS".equals(code)
            || "CAMERA_EQUIPMENT".equals(code)
            || "MUSICAL_INSTRUMENTS".equals(code);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
