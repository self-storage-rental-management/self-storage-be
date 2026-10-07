package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.security.ActorPrincipal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AvailabilityQueryServiceTests {

    @Mock private FacilityRepository facilityRepository;
    @Mock private UnitTypeRepository unitTypeRepository;
    @Mock private FacilityScopeService facilityScopeService;
    @Mock private ReservationCapacityService reservationCapacityService;

    private AvailabilityQueryService service;
    private ActorPrincipal customer;
    private Facility facility;
    private UnitType unitType;
    private UUID facilityId;
    private UUID unitTypeId;

    @BeforeEach
    void setUp() {
        service = new AvailabilityQueryService(
            facilityRepository, unitTypeRepository, facilityScopeService, reservationCapacityService
        );
        customer = new ActorPrincipal(
            UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of()
        );
        facilityId = UUID.randomUUID();
        unitTypeId = UUID.randomUUID();
        facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", facilityId);
        facility.setStatus(FacilityStatus.active);
        unitType = new UnitType();
        ReflectionTestUtils.setField(unitType, "id", unitTypeId);
        unitType.setFacility(facility);
        unitType.setStatus(UnitTypeStatus.active);
    }

    @Test
    void returnsDateRangeAvailabilityForActiveCustomerCatalog() {
        LocalDate startDate = LocalDate.of(2026, 11, 1);
        LocalDate endDate = LocalDate.of(2026, 12, 1);
        when(facilityRepository.findById(facilityId)).thenReturn(Optional.of(facility));
        when(unitTypeRepository.findById(unitTypeId)).thenReturn(Optional.of(unitType));
        when(reservationCapacityService.availableCount(facilityId, unitTypeId, startDate, endDate))
            .thenReturn(2L);

        var response = service.get(customer, facilityId, unitTypeId, startDate, endDate);

        assertThat(response.availableCount()).isEqualTo(2);
        assertThat(response.available()).isTrue();
    }

    @Test
    void rejectsUnitTypeFromAnotherFacility() {
        Facility anotherFacility = new Facility();
        ReflectionTestUtils.setField(anotherFacility, "id", UUID.randomUUID());
        unitType.setFacility(anotherFacility);
        when(facilityRepository.findById(facilityId)).thenReturn(Optional.of(facility));
        when(unitTypeRepository.findById(unitTypeId)).thenReturn(Optional.of(unitType));

        assertThatThrownBy(() -> service.get(
            customer, facilityId, unitTypeId,
            LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("does not belong");
    }

    @Test
    void hidesInactiveUnitTypeFromCustomer() {
        unitType.setStatus(UnitTypeStatus.inactive);
        when(facilityRepository.findById(facilityId)).thenReturn(Optional.of(facility));
        when(unitTypeRepository.findById(unitTypeId)).thenReturn(Optional.of(unitType));

        assertThatThrownBy(() -> service.get(
            customer, facilityId, unitTypeId,
            LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)
        ))
            .isInstanceOf(ApiException.class)
            .hasMessage("Unit type was not found");
    }
}
