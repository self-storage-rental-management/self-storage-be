package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class WorkflowSlaRejectionTests {
    @Test
    void onlyScopedManagerCanRecordRejectionResolutionAndItCannotBeOverwritten() {
        UUID facilityId = UUID.randomUUID();
        UUID checkInId = UUID.randomUUID();
        var facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", facilityId);
        var reservation = new Reservation();
        reservation.setFacility(facility);
        var checkIn = new CheckIn();
        checkIn.setReservation(reservation);
        checkIn.setStatus(CheckInStatus.rejected);
        var em = mock(EntityManager.class);
        var audit = mock(AuditLogService.class);
        when(em.find(CheckIn.class, checkInId, LockModeType.PESSIMISTIC_WRITE)).thenReturn(checkIn);
        var workflow = new WorkflowSlaScheduler(em, null, null, null, null, audit, null, null, null, null);

        var outsideManager = actor(RoleCode.MANAGER, UUID.randomUUID());
        var staff = actor(RoleCode.STAFF, facilityId);
        assertThatThrownBy(() -> workflow.resolveRejection(outsideManager, checkInId, "Đã liên hệ"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus().value()).isEqualTo(404));
        assertThatThrownBy(() -> workflow.resolveRejection(staff, checkInId, "Đã liên hệ"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus().value()).isEqualTo(404));
        var manager = actor(RoleCode.MANAGER, facilityId);
        assertThatThrownBy(() -> workflow.resolveRejection(manager, checkInId, "  "))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus().value()).isEqualTo(400));
        workflow.resolveRejection(manager, checkInId, "  Đã liên hệ  ");
        assertThat(checkIn.getRejectionResolvedAt()).isNotNull();
        assertThat(checkIn.getRejectionResolutionNote()).isEqualTo("Đã liên hệ");
        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.rejected);
        assertThatThrownBy(() -> workflow.resolveRejection(manager, checkInId, "Ghi đè"))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus().value()).isEqualTo(409));
        verify(audit).recordMutation(eq("CHECKIN_REJECTION_FOLLOWUP_RESOLVED"), eq("CheckIn"), eq(checkInId),
            eq(facilityId), isNull(), eq(Map.of("note", "Đã liên hệ")));
    }

    private static ActorPrincipal actor(RoleCode role, UUID facility) {
        return new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(role), Set.of(),
            Map.of(facility, FacilityScopeLevel.MANAGE));
    }
}
