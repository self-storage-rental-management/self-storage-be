package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReservationCapacityService {

    private static final List<StorageUnitStatus> BOOKABLE_UNIT_STATUSES = List.of(
        StorageUnitStatus.available,
        StorageUnitStatus.held,
        StorageUnitStatus.reserved,
        StorageUnitStatus.assigned
    );

    private static final List<ReservationStatus> TEMPORARY_HOLD_STATUSES = List.of(
        ReservationStatus.AWAITING_EMAIL,
        ReservationStatus.AWAITING_REVIEW,
        ReservationStatus.AWAITING_PAYMENT
    );

    private static final List<ReservationStatus> PERMANENT_HOLD_STATUSES = List.of(
        ReservationStatus.CONFIRMED,
        ReservationStatus.UNIT_RESERVED,
        ReservationStatus.READY_FOR_CHECKIN,
        ReservationStatus.AWAITING_CUSTOMER_RECEIPT
    );

    private final UnitTypeRepository unitTypeRepository;
    private final StorageUnitRepository storageUnitRepository;
    private final ReservationRepository reservationRepository;

    public void lockAndRequireAvailableCapacity(
        UUID facilityId,
        UUID unitTypeId,
        LocalDate startDate,
        LocalDate endDate
    ) {
        var lockedUnitType = unitTypeRepository.findByIdForUpdate(unitTypeId)
            .orElseThrow(() -> ApiExceptions.notFound("Unit type was not found"));
        if (!lockedUnitType.getFacility().getId().equals(facilityId)) {
            throw ApiExceptions.validation("Unit type does not belong to the selected facility", null);
        }

        long physicalCapacity = storageUnitRepository
            .countByFacility_IdAndUnitType_IdAndStatusIn(
                facilityId, unitTypeId, BOOKABLE_UNIT_STATUSES
            );
        long heldCapacity = reservationRepository.countCapacityHoldingReservations(
            facilityId, unitTypeId, startDate, endDate, Instant.now(),
            TEMPORARY_HOLD_STATUSES, PERMANENT_HOLD_STATUSES
        );
        if (physicalCapacity - heldCapacity < 1) {
            throw ApiExceptions.conflict(
                "No storage unit is available for the selected rental period"
            );
        }
    }
}
