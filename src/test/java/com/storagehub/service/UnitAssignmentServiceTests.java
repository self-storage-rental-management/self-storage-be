package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.storagehub.api.unitassignment.CreateUnitAssignmentRequest;
import com.storagehub.api.unitassignment.ChangeUnitAssignmentRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitAssignment;
import com.storagehub.domain.model.UnitAssignmentStatus;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitAssignmentRepository;
import com.storagehub.domain.repo.UnitTypeRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.time.Instant;
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
class UnitAssignmentServiceTests {

    @Mock private ReservationRepository reservationRepository;
    @Mock private StorageUnitRepository storageUnitRepository;
    @Mock private UnitAssignmentRepository assignmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AdminAuthorizationService authorizationService;
    @Mock private FacilityScopeService facilityScopeService;
    @Mock private AuditLogService auditLogService;
    @Mock private NotificationService notificationService;
    @Mock private PalletInventoryService palletInventory;
    @Mock private UnitTypeRepository capacityMutex;

    private UnitAssignmentService service;
    private ActorPrincipal actor;
    private User operator;
    private Reservation reservation;
    private StorageUnit unit;

    @BeforeEach
    void setUp() {
        service = new UnitAssignmentService(
            reservationRepository, storageUnitRepository, assignmentRepository, userRepository,
            authorizationService, facilityScopeService, auditLogService, notificationService, palletInventory
        );
        ReflectionTestUtils.setField(service, "capacityMutex", capacityMutex);
        operator = entityWithId(new User());
        UUID facilityId = UUID.randomUUID();
        actor = new ActorPrincipal(
            operator.getId(), UUID.randomUUID(), Set.of(RoleCode.STAFF),
            Set.of("assign_units", "view_reservations", "view_units"),
            Map.of(facilityId, FacilityScopeLevel.OPERATE)
        );

        Facility facility = entityWithId(new Facility());
        facility.setName("Facility A");
        UnitType unitType = entityWithId(new UnitType());
        unitType.setName("Medium");
        lenient().when(capacityMutex.findByIdForUpdate(unitType.getId())).thenReturn(Optional.of(unitType));

        User customer = entityWithId(new User());
        customer.setFullName("Customer A");
        customer.setEmail("customer@example.com");

        reservation = entityWithId(new Reservation());
        reservation.setReservationCode("RSV-001");
        reservation.setCustomer(customer);
        reservation.setFacility(facility);
        reservation.setUnitType(unitType);
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setStartDate(LocalDate.now().plusDays(1));
        reservation.setEndDate(LocalDate.now().plusMonths(1));
        reservation.setConfirmedAt(Instant.now());

        unit = entityWithId(new StorageUnit());
        unit.setFacility(facility);
        unit.setUnitType(unitType);
        unit.setCode("S001");
        unit.setStatus(StorageUnitStatus.available);
    }

    @Test
    void assignsAvailableMatchingUnit() {
        mockAssignmentChecks();

        var response = service.assign(
            actor, reservation.getId(), new CreateUnitAssignmentRequest(unit.getId())
        );

        assertThat(response.assignmentStatus()).isEqualTo(UnitAssignmentStatus.ACTIVE);
        assertThat(response.reservationStatus()).isEqualTo(ReservationStatus.UNIT_RESERVED);
        assertThat(response.storageUnitStatus()).isEqualTo(StorageUnitStatus.reserved);
        assertThat(reservation.getAssignedUnit()).isSameAs(unit);
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.reserved);
        verify(auditLogService).recordMutation(
            any(User.class), any(), any(), any(), any(), any(), any()
        );
        verify(notificationService).createNotification(
            eq(reservation.getCustomer().getId()),
            eq(com.storagehub.domain.model.NotificationType.RESERVATION),
            any(), any(), eq(reservation.getId())
        );
        verify(palletInventory).requireCompatibleAssignment(reservation, unit);
    }

    @Test
    void returnsExistingAssignmentForSameUnitRetry() {
        UnitAssignment current = entityWithId(new UnitAssignment());
        current.setReservation(reservation);
        current.setStorageUnit(unit);
        current.setAssignedBy(operator);
        current.setAssignedAt(Instant.now());
        current.setStatus(UnitAssignmentStatus.ACTIVE);
        reservation.setStatus(ReservationStatus.UNIT_RESERVED);
        reservation.setAssignedUnit(unit);
        unit.setStatus(StorageUnitStatus.reserved);
        when(reservationRepository.findByIdForUpdate(reservation.getId()))
            .thenReturn(Optional.of(reservation));
        when(assignmentRepository.findByReservation_IdAndStatus(
            reservation.getId(), UnitAssignmentStatus.ACTIVE
        )).thenReturn(Optional.of(current));

        var response = service.assign(
            actor, reservation.getId(), new CreateUnitAssignmentRequest(unit.getId())
        );

        assertThat(response.assignmentId()).isEqualTo(current.getId());
        verify(storageUnitRepository, never()).findByIdForUpdate(any());
        verify(assignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUnitFromDifferentType() {
        UnitType otherType = entityWithId(new UnitType());
        unit.setUnitType(otherType);
        when(reservationRepository.findByIdForUpdate(reservation.getId()))
            .thenReturn(Optional.of(reservation));
        when(assignmentRepository.findByReservation_IdAndStatus(
            reservation.getId(), UnitAssignmentStatus.ACTIVE
        )).thenReturn(Optional.empty());
        when(storageUnitRepository.findByIdForUpdate(unit.getId())).thenReturn(Optional.of(unit));

        assertThatThrownBy(() -> service.assign(
            actor, reservation.getId(), new CreateUnitAssignmentRequest(unit.getId())
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("type does not match");

        verify(assignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUnitThatIsNoLongerAvailable() {
        unit.setStatus(StorageUnitStatus.reserved);
        when(reservationRepository.findByIdForUpdate(reservation.getId()))
            .thenReturn(Optional.of(reservation));
        when(assignmentRepository.findByReservation_IdAndStatus(
            reservation.getId(), UnitAssignmentStatus.ACTIVE
        )).thenReturn(Optional.empty());
        when(storageUnitRepository.findByIdForUpdate(unit.getId())).thenReturn(Optional.of(unit));

        assertThatThrownBy(() -> service.assign(
            actor, reservation.getId(), new CreateUnitAssignmentRequest(unit.getId())
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("not available");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void changesAssignmentOnlyToACurrentlyAvailableReplacement(boolean needsMaintenance) {
        reservation.setStatus(ReservationStatus.UNIT_RESERVED);
        UnitAssignment current = entityWithId(new UnitAssignment());
        current.setReservation(reservation);
        current.setStorageUnit(unit);
        current.setAssignedBy(operator);
        current.setAssignedAt(Instant.now());
        current.setStatus(UnitAssignmentStatus.ACTIVE);
        reservation.setAssignedUnit(unit);
        unit.setStatus(StorageUnitStatus.reserved);

        StorageUnit replacement = entityWithId(new StorageUnit());
        replacement.setFacility(unit.getFacility());
        replacement.setUnitType(unit.getUnitType());
        replacement.setCode("S002");
        replacement.setStatus(StorageUnitStatus.available);

        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(assignmentRepository.findByReservation_IdAndStatus(
            reservation.getId(), UnitAssignmentStatus.ACTIVE
        )).thenReturn(Optional.of(current));
        when(storageUnitRepository.findByIdForUpdate(unit.getId())).thenReturn(Optional.of(unit));
        when(storageUnitRepository.findByIdForUpdate(replacement.getId())).thenReturn(Optional.of(replacement));
        when(assignmentRepository.existsByStorageUnit_IdAndStatus(
            replacement.getId(), UnitAssignmentStatus.ACTIVE
        )).thenReturn(false);
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(operator));
        when(assignmentRepository.saveAndFlush(any(UnitAssignment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.change(
            actor, reservation.getId(), new ChangeUnitAssignmentRequest(replacement.getId(), "Kho cũ cần bảo trì", needsMaintenance)
        );

        assertThat(response.storageUnitId()).isEqualTo(replacement.getId());
        assertThat(unit.getStatus()).isEqualTo(needsMaintenance ? StorageUnitStatus.maintenance : StorageUnitStatus.available);
        if (needsMaintenance) assertThat(unit.getAvailableFrom()).isNull();
        assertThat(replacement.getStatus()).isEqualTo(StorageUnitStatus.reserved);
        assertThat(current.getStatus()).isEqualTo(UnitAssignmentStatus.CANCELLED);
        assertThat(reservation.getAssignedUnit()).isSameAs(replacement);
        verify(notificationService).createNotification(
            eq(reservation.getCustomer().getId()),
            eq(com.storagehub.domain.model.NotificationType.RESERVATION),
            eq("Gian kho đã được thay đổi"),
            eq("Đơn RSV-001 đã được chuyển từ gian S001 sang gian S002. Lý do: Kho cũ cần bảo trì. Vui lòng kiểm tra lại thông tin đơn và lịch nhận kho trên tài khoản."),
            eq(reservation.getId())
        );
    }

    @Test
    void rejectsReplacementThatBecameUnavailableBeforeConfirmation() {
        reservation.setStatus(ReservationStatus.UNIT_RESERVED);
        UnitAssignment current = entityWithId(new UnitAssignment());
        current.setReservation(reservation);
        current.setStorageUnit(unit);
        current.setAssignedBy(operator);
        current.setAssignedAt(Instant.now());
        current.setStatus(UnitAssignmentStatus.ACTIVE);
        reservation.setAssignedUnit(unit);
        unit.setStatus(StorageUnitStatus.reserved);

        StorageUnit replacement = entityWithId(new StorageUnit());
        replacement.setFacility(unit.getFacility());
        replacement.setUnitType(unit.getUnitType());
        replacement.setCode("S002");
        replacement.setStatus(StorageUnitStatus.reserved);

        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(assignmentRepository.findByReservation_IdAndStatus(
            reservation.getId(), UnitAssignmentStatus.ACTIVE
        )).thenReturn(Optional.of(current));
        when(storageUnitRepository.findByIdForUpdate(unit.getId())).thenReturn(Optional.of(unit));
        when(storageUnitRepository.findByIdForUpdate(replacement.getId())).thenReturn(Optional.of(replacement));

        assertThatThrownBy(() -> service.change(
            actor, reservation.getId(), new ChangeUnitAssignmentRequest(replacement.getId(), "Kho cũ cần bảo trì")
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("not available");

        verify(assignmentRepository, never()).saveAndFlush(any(UnitAssignment.class));
        assertThat(current.getStatus()).isEqualTo(UnitAssignmentStatus.ACTIVE);
    }

    private void mockAssignmentChecks() {
        when(reservationRepository.findByIdForUpdate(reservation.getId()))
            .thenReturn(Optional.of(reservation));
        when(assignmentRepository.findByReservation_IdAndStatus(
            reservation.getId(), UnitAssignmentStatus.ACTIVE
        )).thenReturn(Optional.empty());
        when(storageUnitRepository.findByIdForUpdate(unit.getId())).thenReturn(Optional.of(unit));
        when(assignmentRepository.existsByStorageUnit_IdAndStatus(
            unit.getId(), UnitAssignmentStatus.ACTIVE
        )).thenReturn(false);
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(operator));
        when(storageUnitRepository.saveAndFlush(unit)).thenReturn(unit);
        when(reservationRepository.saveAndFlush(reservation)).thenReturn(reservation);
        when(assignmentRepository.saveAndFlush(any(UnitAssignment.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private <T> T entityWithId(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }
}
