package com.storagehub.service;

import com.storagehub.api.facility.UnitTypeResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
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

    @Transactional(readOnly = true)
    public PageResponse<UnitTypeResponse> list(
        ActorPrincipal actor,
        UUID facilityId,
        UnitTypeStatus status,
        Pageable pageable,
        String correlationId
    ) {
        Facility facility = facilityRepository.findById(facilityId)
            .orElseThrow(() -> ApiExceptions.notFound("Facility was not found"));
        facilityScopeService.assertCanRead(actor, facility.getId());

        Page<UnitTypeResponse> page = unitTypeRepository.search(facility.getId(), status, pageable)
            .map(this::toResponse);
        return PageResponse.from(page, correlationId);
    }

    private UnitTypeResponse toResponse(UnitType unitType) {
        long availableUnitCount = storageUnitRepository.countByFacility_IdAndUnitType_IdAndStatus(
            unitType.getFacility().getId(),
            unitType.getId(),
            StorageUnitStatus.available
        );
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
}
