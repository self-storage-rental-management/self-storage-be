package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationCapacityServiceTests {

    @Mock private UnitTypeRepository unitTypeRepository;
    @Mock private StorageUnitRepository storageUnitRepository;
    @Mock private ReservationRepository reservationRepository;

    private ReservationCapacityService service;
    private UUID facilityId;
    private UUID unitTypeId;

    @BeforeEach
    void setUp() {
        service = new ReservationCapacityService(
            unitTypeRepository, storageUnitRepository, reservationRepository
        );
        facilityId = UUID.randomUUID();
        unitTypeId = UUID.randomUUID();

        Facility facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", facilityId);
        UnitType unitType = new UnitType();
        ReflectionTestUtils.setField(unitType, "id", unitTypeId);
        unitType.setFacility(facility);
        org.mockito.Mockito.lenient()
            .when(unitTypeRepository.findByIdForUpdate(unitTypeId))
            .thenReturn(Optional.of(unitType));
    }

    @Test
    void allowsReservationWhenOneUnitIsStillAvailable() {
        mockCapacity(3, 2);

        assertThatCode(() -> service.lockAndRequireAvailableCapacity(
            facilityId, unitTypeId,
            LocalDate.of(2026, 10, 10), LocalDate.of(2026, 11, 10)
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsReservationWhenLastUnitIsAlreadyHeld() {
        mockCapacity(1, 1);

        assertThatThrownBy(() -> service.lockAndRequireAvailableCapacity(
            facilityId, unitTypeId,
            LocalDate.of(2026, 10, 10), LocalDate.of(2026, 11, 10)
        ))
            .isInstanceOf(ApiException.class)
            .hasMessage("No storage unit is available for the selected rental period");
    }

    @Test
    void rejectsUnitTypeFromAnotherFacility() {
        assertThatThrownBy(() -> service.lockAndRequireAvailableCapacity(
            UUID.randomUUID(), unitTypeId,
            LocalDate.of(2026, 10, 10), LocalDate.of(2026, 11, 10)
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("does not belong");
    }

    @Test
    void reportsDateAwareAvailableCapacityWithoutReturningNegativeCounts() {
        mockCapacity(2, 5);

        long available = service.availableCount(
            facilityId, unitTypeId,
            LocalDate.of(2026, 10, 10), LocalDate.of(2026, 11, 10)
        );

        assertThat(available).isZero();
    }

    @Test
    void rejectsInvalidAvailabilityDateRange() {
        assertThatThrownBy(() -> service.availableCount(
            facilityId, unitTypeId,
            LocalDate.of(2026, 11, 10), LocalDate.of(2026, 10, 10)
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("valid startDate and endDate");
    }

    private void mockCapacity(long physicalUnits, long heldReservations) {
        when(storageUnitRepository.countByFacility_IdAndUnitType_IdAndStatus(
            eq(facilityId), eq(unitTypeId), eq(com.storagehub.domain.model.StorageUnitStatus.available)
        )).thenReturn(physicalUnits);
        when(reservationRepository.countCapacityHoldingReservations(
            eq(facilityId), eq(unitTypeId), any(), any(), any(), anyCollection(), anyCollection()
        )).thenReturn(heldReservations);
    }
}
