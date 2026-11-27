package com.storagehub.api.returns;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.storagehub.common.api.ApiException;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.*;
import com.storagehub.service.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReturnFeeScheduleControllerTests {
    final ReturnFeeScheduleService schedule = mock(ReturnFeeScheduleService.class);
    final ActorContext actors = mock(ActorContext.class);
    final ReturnCaseRepository returns = mock(ReturnCaseRepository.class);
    final RentalRepository rentals = mock(RentalRepository.class);
    final AdminAuthorizationService authorization = mock(AdminAuthorizationService.class);
    final FacilityScopeService scopes = new FacilityScopeService();
    final ReturnFeeScheduleController controller = new ReturnFeeScheduleController(schedule, actors, returns, rentals, authorization, scopes);
    final UUID id = UUID.randomUUID(), facilityId = UUID.randomUUID(), ownerId = UUID.randomUUID();
    ReturnCase rc;

    @BeforeEach
    void setup() {
        var facility = new Facility(); ReflectionTestUtils.setField(facility, "id", facilityId);
        var owner = new User(); ReflectionTestUtils.setField(owner, "id", ownerId);
        var rental = new Rental(); rental.setCustomer(owner); rental.setFacility(facility);
        rc = new ReturnCase(); rc.setRental(rental); rc.setCustomer(owner); rc.setFacility(facility);
    }

    @Test
    void customerCannotReadAnotherReturnEvenWithFacilityReadScope() {
        var actor = actor(UUID.randomUUID(), RoleCode.CUSTOMER, Map.of(facilityId, FacilityScopeLevel.READ));
        when(actors.required()).thenReturn(actor);
        when(returns.findByIdAndCustomer_Id(id, actor.userId())).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> controller.forReturn(id));
        verify(returns, never()).findById(any());
        verifyNoInteractions(schedule);
    }

    @Test
    void ownerReceivesApplicableSchedule() {
        when(actors.required()).thenReturn(actor(ownerId, RoleCode.CUSTOMER, Map.of()));
        when(returns.findByIdAndCustomer_Id(id, ownerId)).thenReturn(Optional.of(rc));
        controller.forReturn(id);
        verify(schedule).applicableTo(rc.getRental());
        verifyNoInteractions(authorization);
    }

    @Test
    void staffOutsideFacilityCannotReadSchedule() {
        when(actors.required()).thenReturn(actor(UUID.randomUUID(), RoleCode.STAFF, Map.of()));
        when(returns.findById(id)).thenReturn(Optional.of(rc));
        assertThrows(ApiException.class, () -> controller.forReturn(id));
        verifyNoInteractions(schedule);
    }

    @Test
    void staffRequiresPermissionAndFacilityReadScope() {
        var actor = actor(UUID.randomUUID(), RoleCode.STAFF, Map.of(facilityId, FacilityScopeLevel.READ));
        when(actors.required()).thenReturn(actor);
        when(returns.findById(id)).thenReturn(Optional.of(rc));
        doThrow(ApiExceptions.forbidden("Missing permission")).when(authorization).require(actor, SystemPermission.VIEW_RETURNS);
        assertThrows(ApiException.class, () -> controller.forReturn(id));
        verifyNoInteractions(schedule);
        reset(authorization);
        controller.forReturn(id);
        verify(authorization).require(actor, SystemPermission.VIEW_RETURNS);
        verify(schedule).applicableTo(rc.getRental());
    }

    @Test
    void rentalAlsoRequiresCustomerOwnership() {
        when(actors.required()).thenReturn(actor(UUID.randomUUID(), RoleCode.CUSTOMER, Map.of()));
        when(rentals.findById(id)).thenReturn(Optional.of(rc.getRental()));
        assertThrows(ApiException.class, () -> controller.forRental(id));
        verifyNoInteractions(schedule);
    }

    @Test
    void customerCannotPublish() {
        when(actors.required()).thenReturn(actor(ownerId, RoleCode.CUSTOMER, Map.of()));
        assertThrows(ApiException.class, () -> controller.save(null));
        verifyNoInteractions(schedule);
    }

    private ActorPrincipal actor(UUID userId, RoleCode role, Map<UUID, FacilityScopeLevel> scopes) {
        return new ActorPrincipal(userId, UUID.randomUUID(), Set.of(role), Set.of(), scopes);
    }
}
