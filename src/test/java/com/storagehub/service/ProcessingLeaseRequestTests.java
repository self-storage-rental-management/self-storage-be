package com.storagehub.service;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcessingLeaseRequestTests {
    final ProcessingLeaseRepository leases = mock(ProcessingLeaseRepository.class);
    final ReservationRepository reservations = mock(ReservationRepository.class);
    final ProcessingLeaseRequestRepository requests = mock(ProcessingLeaseRequestRepository.class);
    final UserRepository users = mock(UserRepository.class);
    final FacilityScopeService scope = mock(FacilityScopeService.class);
    final ProcessingLeaseService service = new ProcessingLeaseService(leases, reservations,
        mock(CheckInRepository.class), mock(RentalRepository.class), mock(ReturnCaseRepository.class), users,
        mock(AuditLogService.class), scope, mock(EntityManager.class), mock(org.springframework.transaction.PlatformTransactionManager.class));
    final Reservation reservation = new Reservation();
    final ProcessingLease lease = new ProcessingLease();
    final List<ProcessingLeaseRequest> rows = new ArrayList<>();
    final ActorPrincipal owner = actor();
    final ActorPrincipal requester = actor();
    final UUID token = UUID.randomUUID(), waitingToken = UUID.randomUUID();
    static ActorPrincipal actor() {
        return new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.MANAGER),
            Set.of(SystemPermission.ASSIGN_UNITS.code()), Map.of());
    }
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "requests", requests);
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        var facility = new Facility(); ReflectionTestUtils.setField(facility, "id", UUID.randomUUID());
        reservation.setFacility(facility);
        lease.setReservationId(reservation.getId()); lease.setOwnerId(owner.userId()); lease.setSessionId(owner.sessionId());
        lease.setToken(token); lease.setExpiresAt(Instant.now().plusSeconds(120));
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(leases.findForUpdate(reservation.getId())).thenReturn(Optional.of(lease));
        lenient().when(leases.findById(reservation.getId())).thenReturn(Optional.of(lease));
        when(requests.findByReservationIdOrderByRequestedAtAsc(reservation.getId())).thenReturn(rows);
        lenient().when(users.findById(any())).thenReturn(Optional.empty());
        lenient().when(requests.saveAndFlush(any())).thenAnswer(call -> {
            ProcessingLeaseRequest r = call.getArgument(0);
            if (!rows.contains(r)) rows.add(r);
            return r;
        });
    }
    ProcessingLeaseRequest submit() {
        service.requestAccess(requester, ProcessingLeaseService.Target.ASSIGNMENT, reservation.getId(), waitingToken);
        var request = rows.getLast();
        when(requests.findById(request.getId())).thenReturn(Optional.of(request));
        return request;
    }
    @Test void heartbeatDoesNotEnforceTheOldFifteenMinuteCap() {
        lease.setAcquiredAt(Instant.now().minusSeconds(3600));
        lease.setMaxExpiresAt(Instant.now().minusSeconds(1800));
        var result = service.heartbeat(owner, ProcessingLeaseService.Target.ASSIGNMENT, reservation.getId(), token);
        assertThat(result.owned()).isTrue(); assertThat(lease.getMaxExpiresAt()).isNull();
        assertThat(lease.getExpiresAt()).isAfter(Instant.now().plusSeconds(110));
    }
    @Test void approvingTransfersToTheExactRequestingSessionAndToken() {
        var request = submit();
        var result = service.decideRequest(owner, ProcessingLeaseService.Target.ASSIGNMENT, reservation.getId(), token, request.getId(), true);
        assertThat(result.owned()).isFalse();
        assertThat(lease.getOwnerId()).isEqualTo(requester.userId());
        assertThat(lease.getSessionId()).isEqualTo(requester.sessionId());
        assertThat(lease.getToken()).isEqualTo(waitingToken);
        assertThat(request.getStatus()).isEqualTo("APPROVED");
        assertThatThrownBy(() -> service.heartbeat(owner, ProcessingLeaseService.Target.ASSIGNMENT, reservation.getId(), token))
            .hasMessageContaining("chỉ có thể xem");
        assertThat(service.status(requester, ProcessingLeaseService.Target.ASSIGNMENT, reservation.getId(), waitingToken).owned()).isTrue();
    }
    @Test void rejectionKeepsOwnerAndIsVisibleToRequester() {
        var request = submit();
        service.decideRequest(owner, ProcessingLeaseService.Target.ASSIGNMENT, reservation.getId(), token, request.getId(), false);
        assertThat(lease.getToken()).isEqualTo(token);
        var result = service.status(requester, ProcessingLeaseService.Target.ASSIGNMENT, reservation.getId(), waitingToken);
        assertThat(result.requests().getFirst().status()).isEqualTo("REJECTED");
    }
    @Test void onlyTheCurrentOwnerMayDecide() {
        var request = submit();
        assertThatThrownBy(() -> service.decideRequest(requester, ProcessingLeaseService.Target.ASSIGNMENT, reservation.getId(), waitingToken, request.getId(), true))
            .hasMessageContaining("chỉ có thể xem");
        assertThat(request.getStatus()).isEqualTo("PENDING");
    }
    @Test void disconnectedRequesterCannotReceiveOwnership() {
        var request = submit(); request.setExpiresAt(Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> service.decideRequest(owner, ProcessingLeaseService.Target.ASSIGNMENT, reservation.getId(), token, request.getId(), true))
            .hasMessageContaining("hết hạn");
        assertThat(lease.getToken()).isEqualTo(token);
    }
    @Test void requestForAnEarlierOwnerCannotBeApproved() {
        var request = submit(); lease.setToken(UUID.randomUUID());
        assertThatThrownBy(() -> service.decideRequest(owner, ProcessingLeaseService.Target.ASSIGNMENT, reservation.getId(), lease.getToken(), request.getId(), true))
            .hasMessageContaining("hết hạn");
    }
    @Test void repeatedRequestsDoNotDuplicatePendingEntries() {
        service.requestAccess(requester, ProcessingLeaseService.Target.ASSIGNMENT, reservation.getId(), waitingToken);
        service.requestAccess(requester, ProcessingLeaseService.Target.ASSIGNMENT, reservation.getId(), waitingToken);
        assertThat(rows).hasSize(1);
    }
    @Test void requesterMustHavePermissionAndFacilityScope() {
        var unauthorized = new ActorPrincipal(requester.userId(), requester.sessionId(), Set.of(RoleCode.MANAGER), Set.of(), Map.of());
        assertThatThrownBy(() -> service.requestAccess(unauthorized, ProcessingLeaseService.Target.ASSIGNMENT, reservation.getId(), waitingToken))
            .hasMessageContaining("chưa có quyền");
        verify(requests, never()).saveAndFlush(any());
    }
    @Test void leaseStillExpiresAfterDisconnection() {
        lease.setExpiresAt(Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> service.heartbeat(owner, ProcessingLeaseService.Target.ASSIGNMENT, reservation.getId(), token))
            .hasMessageContaining("hết hạn");
    }
}
