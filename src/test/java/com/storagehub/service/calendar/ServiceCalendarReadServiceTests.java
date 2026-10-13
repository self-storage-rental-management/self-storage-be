package com.storagehub.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.FacilityRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.integration.DuongResourceAccess;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServiceCalendarReadServiceTests {
    private final DuongResourceAccess access = mock(DuongResourceAccess.class);
    private final FacilityRepository facilities = mock(FacilityRepository.class);
    private final ServiceCalendarReadService service = new ServiceCalendarReadService(access, facilities);
    private final UUID facilityId = UUID.randomUUID();
    private final ActorPrincipal actor = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(),
        Set.of(RoleCode.STAFF), Set.of(SystemPermission.VIEW_RENTALS.code()),
        Map.of(facilityId, FacilityScopeLevel.READ));

    @Test
    void returnsSharedHoursWithoutCertifyingAvailability() {
        Facility facility = mock(Facility.class);
        when(facility.getId()).thenReturn(facilityId);
        when(facility.getStatus()).thenReturn(FacilityStatus.active);
        when(facilities.findById(facilityId)).thenReturn(Optional.of(facility));
        var view = service.read(actor, facilityId);
        assertThat(view.facilityId()).isEqualTo(facilityId);
        assertThat(view.workingDays()).hasSize(7);
        assertThat(view.workingFrom()).isEqualTo(java.time.LocalTime.of(8, 0));
        assertThat(view.workingTo()).isEqualTo(java.time.LocalTime.of(17, 0));
        assertThat(view.lastAppointmentStart()).isEqualTo(java.time.LocalTime.of(16, 30));
        assertThat(view.slotStepMinutes()).isEqualTo(30);
        assertThat(view.availabilityStatus()).isEqualTo("NOT_EVALUATED");
        verify(access).require(actor, RoleCode.STAFF, facilityId, FacilityScopeLevel.READ, SystemPermission.VIEW_RENTALS);
    }

    @Test
    void currentRolePermissionAndScopeAreCheckedBeforeReadingTheFacility() {
        doThrow(ApiExceptions.notFound("Resource not found")).when(access)
            .require(actor, RoleCode.STAFF, facilityId, FacilityScopeLevel.READ, SystemPermission.VIEW_RENTALS);
        assertThatThrownBy(() -> service.read(actor, facilityId)).hasMessage("Resource not found");
        verifyNoInteractions(facilities);
    }

    @Test
    void missingFacilityDoesNotProduceSyntheticPolicyData() {
        when(facilities.findById(facilityId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.read(actor, facilityId)).hasMessage("Facility not found");
    }
}
