package com.storagehub.service;

import com.storagehub.api.facility.AvailabilityResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.security.ActorPrincipal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AvailabilityQueryService {

    private final FacilityRepository facilityRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final FacilityScopeService facilityScopeService;
    private final ReservationCapacityService reservationCapacityService;

    @Transactional(readOnly = true)
    public AvailabilityResponse get(
        ActorPrincipal actor,
        UUID facilityId,
        UUID unitTypeId,
        LocalDate startDate,
        LocalDate endDate
    ) {
        if (startDate == null || endDate == null || !endDate.isAfter(startDate)) {
            throw ApiExceptions.validation("A valid startDate and endDate are required", null);
        }
        var facility = facilityRepository.findById(facilityId)
            .orElseThrow(() -> ApiExceptions.notFound("Facility was not found"));
        facilityScopeService.assertCanRead(actor, facilityId);
        if (actor.hasRole(RoleCode.CUSTOMER) && facility.getStatus() != FacilityStatus.active) {
            throw ApiExceptions.notFound("Facility was not found");
        }

        UnitType unitType = unitTypeRepository.findById(unitTypeId)
            .orElseThrow(() -> ApiExceptions.notFound("Unit type was not found"));
        if (!unitType.getFacility().getId().equals(facilityId)) {
            throw ApiExceptions.validation("Unit type does not belong to the selected facility", null);
        }
        if (actor.hasRole(RoleCode.CUSTOMER) && unitType.getStatus() != UnitTypeStatus.active) {
            throw ApiExceptions.notFound("Unit type was not found");
        }

        long availableCount = reservationCapacityService.availableCount(
            facilityId, unitTypeId, startDate, endDate
        );
        return new AvailabilityResponse(
            facilityId, unitTypeId, startDate, endDate, availableCount, availableCount > 0
        );
    }
}
