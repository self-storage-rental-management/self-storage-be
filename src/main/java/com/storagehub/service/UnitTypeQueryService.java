package com.storagehub.service;

import com.storagehub.api.facility.PublicUnitTypeResponse;
import com.storagehub.api.facility.UnitTypeResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.model.RoleCode;
import java.time.LocalDate;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.security.ActorPrincipal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UnitTypeQueryService {

    private final FacilityRepository facilityRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final FacilityScopeService facilityScopeService;
    private final ReservationCapacityService reservationCapacityService;

    @Transactional(readOnly = true)
    public PageResponse<UnitTypeResponse> list(
        ActorPrincipal actor,
        UUID facilityId,
        UnitTypeStatus status,
        LocalDate startDate,
        LocalDate endDate,
        Pageable pageable,
        String correlationId
    ) {
        Facility facility = facilityRepository.findById(facilityId)
            .orElseThrow(() -> ApiExceptions.notFound("Facility was not found"));
        facilityScopeService.assertCanRead(actor, facility.getId());

        validateDateRange(startDate, endDate);
        UnitTypeStatus effectiveStatus = actor.hasRole(RoleCode.CUSTOMER) ? UnitTypeStatus.active : status;
        Page<UnitTypeResponse> page = unitTypeRepository.search(facility.getId(), effectiveStatus, pageable)
            .map(unitType -> toResponse(unitType, startDate, endDate));
        return PageResponse.from(page, correlationId);
    }

    @Transactional(readOnly = true)
    public PageResponse<PublicUnitTypeResponse> searchPublicUnitTypes(Pageable pageable, String correlationId) {
        Page<PublicUnitTypeResponse> page = unitTypeRepository
            .searchPublic(FacilityStatus.active, UnitTypeStatus.active, pageable)
            .map(this::toPublicResponse);
        return PageResponse.from(page, correlationId);
    }

    @Transactional(readOnly = true)
    public UnitTypeResponse get(ActorPrincipal actor, UUID unitTypeId, LocalDate startDate, LocalDate endDate) {
        validateDateRange(startDate, endDate);
        UnitType unitType = unitTypeRepository.findById(unitTypeId)
            .orElseThrow(() -> ApiExceptions.notFound("Unit type was not found"));
        facilityScopeService.assertCanRead(actor, unitType.getFacility().getId());
        if (actor.hasRole(RoleCode.CUSTOMER) && unitType.getStatus() != UnitTypeStatus.active) {
            throw ApiExceptions.notFound("Unit type was not found");
        }
        return toResponse(unitType, startDate, endDate);
    }

    private PublicUnitTypeResponse toPublicResponse(UnitType unitType) {
        return new PublicUnitTypeResponse(
            unitType.getId(),
            unitType.getFacility().getId(),
            unitType.getCode(),
            unitType.getName(),
            unitType.getLengthM(),
            unitType.getWidthM(),
            unitType.getHeightM(),
            unitType.getAreaM2(),
            unitType.getVolumeM3(),
            unitType.getMonthlyPrice(),
            unitType.getMaxLoadKg(),
            unitType.getRackCount(),
            unitType.getRackLengthM(),
            unitType.getRackWidthM(),
            unitType.getRackHeightM(),
            storageUnitRepository.countByFacility_IdAndUnitType_IdAndStatus(
                unitType.getFacility().getId(), unitType.getId(), StorageUnitStatus.available
            )
        );
    }

    private UnitTypeResponse toResponse(UnitType unitType, LocalDate startDate, LocalDate endDate) {
        long availableUnitCount = startDate == null
            ? storageUnitRepository.countByFacility_IdAndUnitType_IdAndStatus(
                unitType.getFacility().getId(), unitType.getId(), StorageUnitStatus.available)
            : reservationCapacityService.availableCount(
                unitType.getFacility().getId(), unitType.getId(), startDate, endDate);
        return new UnitTypeResponse(
            unitType.getId(),
            unitType.getFacility().getId(),
            unitType.getCode(),
            unitType.getName(),
            unitType.getLengthM(),
            unitType.getWidthM(),
            unitType.getHeightM(),
            unitType.getAreaM2(),
            unitType.getVolumeM3(),
            unitType.getMonthlyPrice(),
            unitType.getMonthlyPrice(),
            unitType.getImageUrl(),
            unitType.getMaxLoadKg(),
            unitType.getRackCount(),
            unitType.getRackLengthM(),
            unitType.getRackWidthM(),
            unitType.getRackHeightM(),
            unitType.getStatus(),
            availableUnitCount,
            unitType.getCreatedAt(),
            unitType.getUpdatedAt()
        );
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if ((startDate == null) != (endDate == null)) {
            throw ApiExceptions.validation("startDate and endDate must be provided together", null);
        }
        if (startDate != null && !endDate.isAfter(startDate)) {
            throw ApiExceptions.validation("endDate must be after startDate", null);
        }
    }
}
