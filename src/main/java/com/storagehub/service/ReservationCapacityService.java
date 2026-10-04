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

    private static final List<ReservationStatus> TEMPORARY_HOLD_STATUSES = List.of(
        ReservationStatus.AWAITING_EMAIL,
        ReservationStatus.AWAITING_REVIEW,
        ReservationStatus.AWAITING_PAYMENT,
        ReservationStatus.PAYMENT_GRACE
    );

    private static final List<ReservationStatus> PERMANENT_HOLD_STATUSES = List.of(
        ReservationStatus.CONFIRMED,
        ReservationStatus.PAYMENT_REVIEW,
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

        if (availableCount(facilityId, unitTypeId, startDate, endDate) < 1) {
            throw ApiExceptions.conflict(
                "No storage unit is available for the selected rental period"
            );
        }
    }

    public void requireNoCustomerOverlappingHold(
        UUID customerId,
        UUID facilityId,
        UUID unitTypeId,
        LocalDate startDate,
        LocalDate endDate
    ) {
        long existingHolds = reservationRepository.countCustomerOverlappingCapacityHolds(
            customerId, facilityId, unitTypeId, startDate, endDate, Instant.now(),
            TEMPORARY_HOLD_STATUSES, PERMANENT_HOLD_STATUSES
        );
        if (existingHolds > 0) {
            throw ApiExceptions.conflict(
                "You already have an active reservation for this unit type and rental period"
            );
        }
    }

    public long availableCount(
        UUID facilityId,
        UUID unitTypeId,
        LocalDate startDate,
        LocalDate endDate
    ) {
        if (startDate == null || endDate == null || !endDate.isAfter(startDate)) {
            throw ApiExceptions.validation("A valid startDate and endDate are required", null);
        }
        // A physical unit is sellable only while operations explicitly keep it AVAILABLE.
        // Assignment, occupancy, return, cleaning and maintenance must move it out of that
        // state; a contract/reservation end date never makes it available automatically.
        long operationalAvailableUnits = storageUnitRepository
            .countByFacility_IdAndUnitType_IdAndStatus(
                facilityId, unitTypeId, StorageUnitStatus.available
            );
        Instant now = Instant.now();
        long unassignedActiveHolds = reservationRepository.countCapacityHoldingReservations(
            facilityId, unitTypeId, startDate, endDate, now,
            TEMPORARY_HOLD_STATUSES, PERMANENT_HOLD_STATUSES
        );
        return Math.max(0, operationalAvailableUnits - unassignedActiveHolds);
    }
}
