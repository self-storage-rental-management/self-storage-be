package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProcessingLeaseReclaimTests {
    private final ProcessingLeaseRepository leases = mock(ProcessingLeaseRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ProcessingLeaseService service = new ProcessingLeaseService(
        leases, reservations, null, null, null, users, mock(AuditLogService.class),
        mock(FacilityScopeService.class), mock(EntityManager.class), null);
    private final UUID id = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final UUID oldToken = UUID.randomUUID();

    private ProcessingLease prepare() {
        Reservation reservation = mock(Reservation.class);
        Facility facility = mock(Facility.class);
        when(reservation.getId()).thenReturn(id);
        when(reservation.getFacility()).thenReturn(facility);
        when(reservations.findById(id)).thenReturn(Optional.of(reservation));
        ProcessingLease lease = new ProcessingLease();
        lease.setOwnerId(owner);
        lease.setSessionId(session);
        lease.setToken(oldToken);
        lease.setExpiresAt(Instant.now().plusSeconds(120));
        when(leases.findForUpdate(id)).thenReturn(Optional.of(lease));
        when(leases.findById(id)).thenReturn(Optional.of(lease));
        return lease;
    }

    private ActorPrincipal actor(UUID user) {
        return new ActorPrincipal(user, session, Set.of(RoleCode.STAFF),
            Set.of(SystemPermission.PERFORM_CHECKIN.code()), Map.of());
    }

    @Test
    void explicitReclaimReplacesOwnTokenAndOldCleanupCannotReleaseIt() {
        ProcessingLease lease = prepare();
        UUID newToken = UUID.randomUUID();
        assertThat(service.acquire(actor(owner), ProcessingLeaseService.Target.RESERVATION, id, newToken, true).owned()).isTrue();
        service.release(actor(owner), ProcessingLeaseService.Target.RESERVATION, id, oldToken);
        assertThat(lease.getToken()).isEqualTo(newToken);
        assertThat(service.status(actor(owner), ProcessingLeaseService.Target.RESERVATION, id, oldToken).owned()).isFalse();
    }

    @Test
    void ordinaryAcquireDoesNotStealAnotherTabOfSameUser() {
        ProcessingLease lease = prepare();
        assertThat(service.acquire(actor(owner), ProcessingLeaseService.Target.RESERVATION, id, UUID.randomUUID()).owned()).isFalse();
        assertThat(lease.getToken()).isEqualTo(oldToken);
    }

    @Test
    void reclaimCannotTakeAnotherPersonsLease() {
        ProcessingLease lease = prepare();
        assertThat(service.acquire(actor(UUID.randomUUID()), ProcessingLeaseService.Target.RESERVATION, id, UUID.randomUUID(), true).owned()).isFalse();
        assertThat(lease.getToken()).isEqualTo(oldToken);
        verify(leases, never()).saveAndFlush(any());
    }
}
