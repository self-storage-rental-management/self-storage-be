package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.api.unitrelease.ReleaseAssignedUnitRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitAssignment;
import com.storagehub.domain.model.UnitAssignmentStatus;
import com.storagehub.domain.model.UnitReleaseDisposition;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.CheckInRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitAssignmentRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
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
class CancelledReservationUnitReleaseServiceTests {

    @Mock private UnitAssignmentRepository assignmentRepository;
    @Mock private ReservationRepository reservationRepository;
    @Mock private StorageUnitRepository storageUnitRepository;
    @Mock private CheckInRepository checkInRepository;
    @Mock private RentalRepository rentalRepository;
    @Mock private UserRepository userRepository;
    @Mock private AdminAuthorizationService authorizationService;
    @Mock private FacilityScopeService facilityScopeService;
    @Mock private AuditLogService auditLogService;

    private CancelledReservationUnitReleaseService service;
    private ActorPrincipal actor;
    private User staff;
    private Reservation reservation;
    private StorageUnit unit;
    private UnitAssignment assignment;

    @BeforeEach
    void setUp() {
        service = new CancelledReservationUnitReleaseService(
            assignmentRepository, reservationRepository, storageUnitRepository,
            checkInRepository, rentalRepository, userRepository,
            authorizationService, facilityScopeService, auditLogService
        );

        staff = entityWithId(new User());
        actor = new ActorPrincipal(
            staff.getId(), UUID.randomUUID(), Set.of(RoleCode.STAFF),
            Set.of("assign_units"),
            Map.of(UUID.randomUUID(), FacilityScopeLevel.OPERATE)
        );
        Facility facility = entityWithId(new Facility());
        facility.setName("Facility A");
        UnitType unitType = entityWithId(new UnitType());

        unit = entityWithId(new StorageUnit());
        unit.setFacility(facility);
        unit.setUnitType(unitType);
        unit.setCode("S001");
        unit.setStatus(StorageUnitStatus.reserved);

        reservation = entityWithId(new Reservation());
        reservation.setReservationCode("RSV-001");
        reservation.setFacility(facility);
        reservation.setUnitType(unitType);
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setAssignedUnit(unit);

        assignment = entityWithId(new UnitAssignment());
        assignment.setReservation(reservation);
        assignment.setStorageUnit(unit);
        assignment.setAssignedBy(staff);
        assignment.setAssignedAt(Instant.now().minusSeconds(3600));
        assignment.setStatus(UnitAssignmentStatus.ACTIVE);
    }

    @Test
    void releasesUnusedUnitAsAvailable() {
        mockReleaseChecks();
        ReleaseAssignedUnitRequest request = new ReleaseAssignedUnitRequest(
            assignment.getId(), UnitReleaseDisposition.AVAILABLE, "Reservation cancelled"
        );

        var response = service.release(actor, reservation.getId(), "release-key-1", request);

        assertThat(response.assignmentStatus()).isEqualTo(UnitAssignmentStatus.CANCELLED);
        assertThat(response.previousStorageUnitStatus()).isEqualTo(StorageUnitStatus.reserved);
        assertThat(response.storageUnitStatus()).isEqualTo(StorageUnitStatus.available);
        assertThat(reservation.getAssignedUnit()).isNull();
        assertThat(unit.getLastReleasedAt()).isNotNull();
    }

    @Test
    void routesUnitDirectlyToMaintenanceWithoutOwningMaintenanceWork() {
        mockReleaseChecks();
        ReleaseAssignedUnitRequest request = new ReleaseAssignedUnitRequest(
            assignment.getId(), UnitReleaseDisposition.MAINTENANCE, "Cleaning required"
        );

        var response = service.release(actor, reservation.getId(), "release-key-2", request);

        assertThat(response.storageUnitStatus()).isEqualTo(StorageUnitStatus.maintenance);
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.maintenance);
    }

    @Test
    void rejectsReleaseAfterCompletedCheckIn() {
        when(assignmentRepository.findByCancelledBy_IdAndCancellationIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(reservationRepository.findByIdForUpdate(reservation.getId()))
            .thenReturn(Optional.of(reservation));
        when(assignmentRepository.findByIdForUpdate(assignment.getId()))
            .thenReturn(Optional.of(assignment));
        when(storageUnitRepository.findByIdForUpdate(unit.getId())).thenReturn(Optional.of(unit));
        when(checkInRepository.existsByReservation_IdAndStatus(
            reservation.getId(), CheckInStatus.completed
        )).thenReturn(true);

        assertThatThrownBy(() -> service.release(
            actor, reservation.getId(), "release-key-3",
            new ReleaseAssignedUnitRequest(
                assignment.getId(), UnitReleaseDisposition.AVAILABLE, "Cancelled"
            )
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("check-in is completed");

        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.reserved);
        assertThat(assignment.getStatus()).isEqualTo(UnitAssignmentStatus.ACTIVE);
    }

    @Test
    void rejectsReleaseWhenActiveRentalExists() {
        when(assignmentRepository.findByCancelledBy_IdAndCancellationIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(reservationRepository.findByIdForUpdate(reservation.getId()))
            .thenReturn(Optional.of(reservation));
        when(assignmentRepository.findByIdForUpdate(assignment.getId()))
            .thenReturn(Optional.of(assignment));
        when(storageUnitRepository.findByIdForUpdate(unit.getId())).thenReturn(Optional.of(unit));
        when(rentalRepository.existsActiveRental(
            reservation.getId(), unit.getId(), RentalStatus.active
        )).thenReturn(true);

        assertThatThrownBy(() -> service.release(
            actor, reservation.getId(), "release-key-4",
            new ReleaseAssignedUnitRequest(
                assignment.getId(), UnitReleaseDisposition.AVAILABLE, "Cancelled"
            )
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("active rental exists");
    }

    @Test
    void returnsOriginalResultForIdempotentRetry() {
        assignment.setStatus(UnitAssignmentStatus.CANCELLED);
        assignment.setCancelledBy(staff);
        assignment.setCancelledAt(Instant.now());
        assignment.setReleaseDisposition(UnitReleaseDisposition.AVAILABLE);
        assignment.setPreviousStorageUnitStatus(StorageUnitStatus.reserved);
        unit.setStatus(StorageUnitStatus.available);
        ReleaseAssignedUnitRequest request = new ReleaseAssignedUnitRequest(
            assignment.getId(), UnitReleaseDisposition.AVAILABLE, "Cancelled"
        );
        String fingerprint = invokeFingerprint(request);
        assignment.setCancellationRequestFingerprint(fingerprint);
        when(assignmentRepository.findByCancelledBy_IdAndCancellationIdempotencyKey(
            actor.userId(), "same-key"
        )).thenReturn(Optional.of(assignment));

        var response = service.release(actor, reservation.getId(), "same-key", request);

        assertThat(response.assignmentStatus()).isEqualTo(UnitAssignmentStatus.CANCELLED);
        verify(reservationRepository, never()).findByIdForUpdate(any());
    }

    private void mockReleaseChecks() {
        when(assignmentRepository.findByCancelledBy_IdAndCancellationIdempotencyKey(any(), any()))
            .thenReturn(Optional.empty());
        when(reservationRepository.findByIdForUpdate(reservation.getId()))
            .thenReturn(Optional.of(reservation));
        when(assignmentRepository.findByIdForUpdate(assignment.getId()))
            .thenReturn(Optional.of(assignment));
        when(storageUnitRepository.findByIdForUpdate(unit.getId())).thenReturn(Optional.of(unit));
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(storageUnitRepository.saveAndFlush(unit)).thenReturn(unit);
        when(reservationRepository.saveAndFlush(reservation)).thenReturn(reservation);
        when(assignmentRepository.saveAndFlush(assignment)).thenReturn(assignment);
    }

    private String invokeFingerprint(ReleaseAssignedUnitRequest request) {
        return ReflectionTestUtils.invokeMethod(
            service, "fingerprint", reservation.getId(), request
        );
    }

    private <T> T entityWithId(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }
}
