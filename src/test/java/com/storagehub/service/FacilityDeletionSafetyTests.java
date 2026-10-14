package com.storagehub.service;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class FacilityDeletionSafetyTests {
    @Mock FacilityRepository facilities;
    @Mock UnitTypeRepository unitTypes;
    @Mock StorageUnitRepository units;
    @Mock RentalPackagePolicyRepository packages;
    @Mock FacilityScopeService scopes;
    @Mock AdminAuthorizationService authorization;
    @Mock AuditLogService audit;
    @Mock UserFacilityScopeRepository userFacilityScopes;
    @Mock ReservationRepository reservations;
    @InjectMocks FacilityLifecycleService service;

    private final UUID id = UUID.randomUUID();
    private final ActorPrincipal actor = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(),
        Set.of(RoleCode.BUSINESS), Set.of(SystemPermission.MANAGE_POLICIES.code()), Map.of());

    @Test void preservesFacilityWithReservationHistory() {
        when(facilities.findById(id)).thenReturn(Optional.of(new Facility()));
        when(reservations.existsByFacility_Id(id)).thenReturn(true);
        assertThatThrownBy(() -> service.deleteFacility(actor, id)).hasMessageContaining("hồ sơ");
        verify(facilities, never()).delete(any());
    }

    @Test void preservesFacilityWithPhysicalUnits() {
        when(facilities.findById(id)).thenReturn(Optional.of(new Facility()));
        when(units.countByFacility_Id(id)).thenReturn(1L);
        assertThatThrownBy(() -> service.deleteFacility(actor, id)).hasMessageContaining("Ngừng hoạt động");
        verify(facilities, never()).delete(any());
    }

    @Test void allowsDeletingEmptyFacility() {
        Facility facility = new Facility();
        when(facilities.findById(id)).thenReturn(Optional.of(facility));
        service.deleteFacility(actor, id);
        verify(facilities).delete(facility);
        verify(facilities).flush();
    }

    @Test void rejectsCustomerDeletion() {
        ActorPrincipal customer = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of());
        assertThatThrownBy(() -> service.deleteFacility(customer, id)).hasMessageContaining("Only Business");
        verifyNoInteractions(facilities);
    }
}
