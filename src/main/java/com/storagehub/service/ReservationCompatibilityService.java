package com.storagehub.service;

import com.storagehub.api.reservation.CompatibilityCheckRequest;
import com.storagehub.api.reservation.CompatibilityCheckResponse;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.api.reservation.ReservationRecommendationRequest;
import com.storagehub.api.reservation.ReservationRecommendationResponse;
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
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationCompatibilityService {

    private static final BigDecimal CUBIC_CENTIMETERS_PER_CUBIC_METER = new BigDecimal("1000000");
    private static final BigDecimal RACK_UTILIZATION_RATE = new BigDecimal("0.80");

    private final FacilityRepository facilityRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final RentalPackagePolicyRepository policyRepository;
    private final ReservationCapacityService capacityService;
    private final GoodsCatalogService goodsCatalogService;

    @Autowired
    public ReservationCompatibilityService(
        FacilityRepository facilityRepository,
        UnitTypeRepository unitTypeRepository,
        RentalPackagePolicyRepository policyRepository,
        ReservationCapacityService capacityService,
        GoodsCatalogService goodsCatalogService
    ) {
        this.facilityRepository = facilityRepository;
        this.unitTypeRepository = unitTypeRepository;
        this.policyRepository = policyRepository;
        this.capacityService = capacityService;
        this.goodsCatalogService = goodsCatalogService;
    }

    // Kept for focused unit tests; production always injects the database-backed catalog.
    ReservationCompatibilityService(
        FacilityRepository facilityRepository,
        UnitTypeRepository unitTypeRepository,
        ReservationCapacityService capacityService
    ) {
        this(facilityRepository, unitTypeRepository, null, capacityService, null);
    }

    @Transactional(readOnly = true)
    public CompatibilityCheckResponse check(ActorPrincipal actor, CompatibilityCheckRequest request) {
        return check(actor, request, true);
    }

    @Transactional(readOnly = true)
    public CompatibilityCheckResponse checkQuotedSnapshot(ActorPrincipal actor, CompatibilityCheckRequest request) {
        return check(actor, request, false);
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
        List<String> issues = new ArrayList<>();
        List<RecommendationCandidate> compatibleCandidates = new ArrayList<>();

        for (UnitType candidate : candidates) {
            CompatibilityCheckResponse result;
            try {
                result = check(actor, new CompatibilityCheckRequest(
                    facility.getId(), candidate.getId(), request.getStartDate(), request.getEndDate(),
                    request.getGoodsCondition(), copyItems(request.getGoodsItems())
                ));
            } catch (ApiException exception) {
                incompatibleIds.add(candidate.getId());
                addIssue(issues, exception.getMessage());
                continue;
            }

            if (result.getResult() == CompatibilityResult.INCOMPATIBLE || result.getAvailableUnitCount() <= 0) {
                incompatibleIds.add(candidate.getId());
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
            List.copyOf(issues)
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
            return copy;
        }).toList();
    }

    private record RecommendationCandidate(UnitType unitType, CompatibilityCheckResponse result) {}

    private CompatibilityCheckResponse check(ActorPrincipal actor, CompatibilityCheckRequest request, boolean validateCurrentCatalog) {
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
        List<String> issues = new ArrayList<>();

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
            if (!fitsInsideRack(item, unitType)) {
                issues.add("goodsItems[" + index + "] does not fit inside one rack in any orientation");
            }
            reviewRequired = reviewRequired || isOther(item);
        }

        BigDecimal unitVolume = unitType.getVolumeM3();
        BigDecimal usableVolumePerRack = unitType.getRackLengthM()
            .multiply(unitType.getRackWidthM())
            .multiply(unitType.getRackHeightM())
            .multiply(RACK_UTILIZATION_RATE);
        int requiredRackCount = usableVolumePerRack.signum() > 0
            ? totalVolume.divide(usableVolumePerRack, 0, RoundingMode.CEILING).intValueExact()
            : Integer.MAX_VALUE;
        if (unitType.getRackCount() <= 0 || requiredRackCount > unitType.getRackCount()) {
            issues.add("Total goods volume requires more racks than the selected unit type provides");
        }
        if (totalWeight.compareTo(unitType.getMaxLoadKg()) > 0) {
            issues.add("Total goods weight exceeds the selected unit type capacity");
        }
        if (availableCount == 0) {
            issues.add("No physical unit is currently available for the selected unit type");
        }

        CompatibilityResult result = !issues.isEmpty()
            ? CompatibilityResult.INCOMPATIBLE
            : reviewRequired ? CompatibilityResult.REVIEW_REQUIRED : CompatibilityResult.COMPATIBLE;

        return new CompatibilityCheckResponse(
            facility.getId(), unitType.getId(), request.getStartDate(), request.getEndDate(), result,
            totalVolume.setScale(6, RoundingMode.HALF_UP),
            totalWeight.setScale(2, RoundingMode.HALF_UP),
            unitVolume.setScale(6, RoundingMode.HALF_UP),
            unitType.getMaxLoadKg().setScale(2, RoundingMode.HALF_UP),
            RACK_UTILIZATION_RATE,
            usableVolumePerRack.setScale(6, RoundingMode.HALF_UP),
            requiredRackCount,
            unitType.getRackCount(),
            availableCount, reviewRequired, List.copyOf(issues)
        );
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
        if (isOther(item)
            && (isBlank(item.getCustomGoodsName()) || isBlank(item.getCustomMaterial()))) {
            throw ApiExceptions.validation(
                "goodsItems[" + index + "] requires customGoodsName and customMaterial for OTHER category", null
            );
        }
        if (!isOther(item) && isBlank(item.getMaterialName())) {
            throw ApiExceptions.validation(
                "goodsItems[" + index + "] requires materialName for a standard category", null
            );
        }
        // The database-backed catalog and per-unit-type scope are validated together by the caller.
    }

    private boolean fitsInsideRack(GoodsItemRequest item, UnitType unitType) {
        List<BigDecimal> itemDimensions = new ArrayList<>(List.of(
            item.getLengthCm(), item.getWidthCm(), item.getHeightCm()
        ));
        List<BigDecimal> rackDimensions = new ArrayList<>(List.of(
            unitType.getRackLengthM().movePointRight(2),
            unitType.getRackWidthM().movePointRight(2),
            unitType.getRackHeightM().movePointRight(2)
        ));
        itemDimensions.sort(Comparator.naturalOrder());
        rackDimensions.sort(Comparator.naturalOrder());
        for (int index = 0; index < itemDimensions.size(); index++) {
            if (itemDimensions.get(index).compareTo(rackDimensions.get(index)) > 0) {
                return false;
            }
        }
        return true;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
