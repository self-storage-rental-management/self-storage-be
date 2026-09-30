package com.storagehub.service;

import com.storagehub.api.facility.FacilityResponse;
import com.storagehub.api.facility.StorageUnitResponse;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.security.ActorPrincipal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FacilityQueryService {

    private static final UUID EMPTY_SCOPE = new UUID(0, 0);

    private final FacilityRepository facilityRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final FacilityScopeService facilityScopeService;

    @Transactional(readOnly = true)
    public PageResponse<FacilityResponse> searchFacilities(
        ActorPrincipal actor,
        FacilityStatus status,
        String city,
        String query,
        Pageable pageable,
        String correlationId
    ) {
        boolean scoped = facilityScopeService.isFacilityScoped(actor);
        List<UUID> facilityIds = actor.facilityScopes().isEmpty()
            ? List.of(EMPTY_SCOPE)
            : actor.facilityScopes().keySet().stream().toList();
        Page<FacilityResponse> page = facilityRepository.search(status, clean(city), clean(query), scoped, facilityIds, pageable)
            .map(this::toFacilityResponse);
        return PageResponse.from(page, correlationId);
    }

    @Transactional(readOnly = true)
    public PageResponse<StorageUnitResponse> searchUnits(
        ActorPrincipal actor,
        UUID facilityId,
        StorageUnitStatus status,
        UUID unitTypeId,
        Pageable pageable,
        String correlationId
    ) {
        if (facilityScopeService.isFacilityScoped(actor) && facilityId == null) {
            throw com.storagehub.common.api.ApiExceptions.validation("facilityId is required for facility-scoped actors", null);
        }
        if (facilityId != null) {
            facilityScopeService.assertCanRead(actor, facilityId);
        }
        Page<StorageUnitResponse> page = storageUnitRepository.search(facilityId, status, unitTypeId, pageable)
            .map(this::toStorageUnitResponse);
        return PageResponse.from(page, correlationId);
    }

    private FacilityResponse toFacilityResponse(Facility facility) {
        return new FacilityResponse(
            facility.getId(),
            facility.getCode(),
            facility.getName(),
            facility.getAddress(),
            facility.getCity(),
            facility.getStatus(),
            facility.getCreatedAt(),
            facility.getUpdatedAt()
        );
    }

    private StorageUnitResponse toStorageUnitResponse(StorageUnit unit) {
        return new StorageUnitResponse(
            unit.getId(),
            unit.getFacility().getId(),
            unit.getUnitType().getId(),
            unit.getCode(),
            unit.getFloor(),
            unit.getZone(),
            unit.getStatus()
        );
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
