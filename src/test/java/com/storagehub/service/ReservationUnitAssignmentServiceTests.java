package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storagehub.api.reservation.AssignStorageUnitRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UserRepository;
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
class ReservationUnitAssignmentServiceTests {

    @Mock private ReservationRepository reservationRepository;
    @Mock private StorageUnitRepository storageUnitRepository;
    @Mock private UserRepository userRepository;
    @Mock private AdminAuthorizationService authorizationService;
    @Mock private FacilityScopeService facilityScopeService;
    @Mock private NotificationService notificationService;
    @Mock private AuditLogService auditLogService;

    private ReservationUnitAssignmentService service;
    private ActorPrincipal staff;
    private User staffUser;
    private Reservation reservation;
    private StorageUnit storageUnit;

    @BeforeEach
    void setUp() {
        service = new ReservationUnitAssignmentService(
            reservationRepository, storageUnitRepository, userRepository,
            authorizationService, facilityScopeService, notificationService, auditLogService
        );
        staffUser = entityWithId(new User());
        staff = new ActorPrincipal(
            staffUser.getId(), UUID.randomUUID(), Set.of(RoleCode.STAFF),
            Set.of("assign_units"), Map.of()
        );

        Facility facility = entityWithId(new Facility());
        UnitType unitType = entityWithId(new UnitType());
        User customer = entityWithId(new User());
        customer.setEmail("customer@example.com");

        reservation = entityWithId(new Reservation());
        reservation.setReservationCode("RSV-ASSIGN001");
        reservation.setCustomer(customer);
        reservation.setFacility(facility);
        reservation.setUnitType(unitType);
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setStartDate(LocalDate.of(2026, 10, 10));
        reservation.setEndDate(LocalDate.of(2026, 11, 10));

        storageUnit = entityWithId(new StorageUnit());
        storageUnit.setCode("A-101");
        storageUnit.setFacility(facility);
        storageUnit.setUnitType(unitType);
        storageUnit.setStatus(StorageUnitStatus.available);
    }

    @Test
    void assignsAvailableUnitToConfirmedReservation() {
        mockAssignmentData();

        var response = service.assign(
            staff, reservation.getId(), new AssignStorageUnitRequest(storageUnit.getId())
        );

        assertThat(response.getReservationStatus()).isEqualTo(ReservationStatus.UNIT_RESERVED);
        assertThat(response.getStorageUnitCode()).isEqualTo("A-101");
        assertThat(storageUnit.getStatus()).isEqualTo(StorageUnitStatus.reserved);
        assertThat(reservation.getAssignedUnitBy()).isEqualTo(staffUser);
        verify(notificationService).createNotification(
            customerId(),
            com.storagehub.domain.model.NotificationType.RESERVATION,
            "Kho đã được cấp cho đơn đặt chỗ",
            "Đơn RSV-ASSIGN001 đã được cấp kho A-101.",
            reservation.getId()
        );
    }

    @Test
    void rejectsUnitThatIsAlreadyReserved() {
        storageUnit.setStatus(StorageUnitStatus.reserved);
        when(reservationRepository.findById(reservation.getId()))
            .thenReturn(Optional.of(reservation));
        when(storageUnitRepository.findByIdForUpdate(storageUnit.getId()))
            .thenReturn(Optional.of(storageUnit));

        assertThatThrownBy(() -> service.assign(
            staff, reservation.getId(), new AssignStorageUnitRequest(storageUnit.getId())
        ))
            .isInstanceOf(ApiException.class)
            .hasMessage("Storage unit is not available");
    }

    @Test
    void rejectsAssignmentBeforeReservationIsConfirmed() {
        reservation.setStatus(ReservationStatus.AWAITING_PAYMENT);
        when(reservationRepository.findById(reservation.getId()))
            .thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.assign(
            staff, reservation.getId(), new AssignStorageUnitRequest(storageUnit.getId())
        ))
            .isInstanceOf(ApiException.class)
            .hasMessage("Reservation is not awaiting unit assignment");
    }

    @Test
    void customerCannotAssignUnit() {
        ActorPrincipal customer = new ActorPrincipal(
            UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of()
        );

        assertThatThrownBy(() -> service.assign(
            customer, reservation.getId(), new AssignStorageUnitRequest(storageUnit.getId())
        ))
            .isInstanceOf(ApiException.class)
            .hasMessage("Only staff can assign storage units");
    }

    private void mockAssignmentData() {
        when(reservationRepository.findById(reservation.getId()))
            .thenReturn(Optional.of(reservation));
        when(storageUnitRepository.findByIdForUpdate(storageUnit.getId()))
            .thenReturn(Optional.of(storageUnit));
        when(userRepository.findById(staff.userId())).thenReturn(Optional.of(staffUser));
        when(storageUnitRepository.saveAndFlush(storageUnit)).thenReturn(storageUnit);
        when(reservationRepository.saveAndFlush(reservation)).thenReturn(reservation);
    }

    private UUID customerId() {
        return reservation.getCustomer().getId();
    }

    private <T> T entityWithId(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }
}
