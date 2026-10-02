package com.storagehub.service;

import com.storagehub.api.reservation.CompatibilityCheckRequest;
import com.storagehub.api.reservation.CompatibilityCheckResponse;
import com.storagehub.api.reservation.GoodsItemRequest;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.CompatibilityResult;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.GoodsCategory;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationCompatibilityService {

    private static final BigDecimal CUBIC_CENTIMETERS_PER_CUBIC_METER = new BigDecimal("1000000");

    private final FacilityRepository facilityRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final StorageUnitRepository storageUnitRepository;

    @Transactional(readOnly = true)
    public CompatibilityCheckResponse check(ActorPrincipal actor, CompatibilityCheckRequest request) {
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

        long availableCount = storageUnitRepository.countByFacility_IdAndUnitType_IdAndStatus(
            facility.getId(), unitType.getId(), StorageUnitStatus.available
        );
        BigDecimal totalVolume = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        boolean reviewRequired = false;
        List<String> issues = new ArrayList<>();

        for (int index = 0; index < request.getGoodsItems().size(); index++) {
            GoodsItemRequest item = request.getGoodsItems().get(index);
            validateOtherItem(item, index);
            BigDecimal quantity = BigDecimal.valueOf(item.getQuantity());
            BigDecimal itemVolume = item.getLengthCm()
                .multiply(item.getWidthCm())
                .multiply(item.getHeightCm())
                .divide(CUBIC_CENTIMETERS_PER_CUBIC_METER, 6, RoundingMode.HALF_UP)
                .multiply(quantity);
            totalVolume = totalVolume.add(itemVolume);
            totalWeight = totalWeight.add(item.getWeightPerItemKg().multiply(quantity));
            if (!fitsInsideUnit(item, unitType)) {
                issues.add("goodsItems[" + index + "] does not fit inside the selected unit type");
            }
            reviewRequired = reviewRequired || item.getCategory() == GoodsCategory.OTHER;
        }

        BigDecimal unitVolume = unitType.getVolumeM3();
        if (totalVolume.compareTo(unitVolume) > 0) {
            issues.add("Total goods volume exceeds the selected unit type capacity");
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

    private void validateOtherItem(GoodsItemRequest item, int index) {
        if (item.getCategory() == GoodsCategory.OTHER
            && (isBlank(item.getCustomGoodsName()) || isBlank(item.getCustomMaterial()))) {
            throw ApiExceptions.validation(
                "goodsItems[" + index + "] requires customGoodsName and customMaterial for OTHER category", null
            );
        }
    }

    private boolean fitsInsideUnit(GoodsItemRequest item, UnitType unitType) {
        List<BigDecimal> itemDimensions = new ArrayList<>(List.of(
            item.getLengthCm(), item.getWidthCm(), item.getHeightCm()
        ));
        List<BigDecimal> unitDimensions = new ArrayList<>(List.of(
            unitType.getLengthM().movePointRight(2),
            unitType.getWidthM().movePointRight(2),
            unitType.getHeightM().movePointRight(2)
        ));
        itemDimensions.sort(Comparator.naturalOrder());
        unitDimensions.sort(Comparator.naturalOrder());
        for (int index = 0; index < itemDimensions.size(); index++) {
            if (itemDimensions.get(index).compareTo(unitDimensions.get(index)) > 0) {
                return false;
            }
        }
        return true;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
