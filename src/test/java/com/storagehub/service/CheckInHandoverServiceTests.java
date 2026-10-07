package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.checkin.CheckInChecklist;
import com.storagehub.api.checkin.CheckInMeasurements;
import com.storagehub.api.checkin.CompleteCheckInRequest;
import com.storagehub.api.checkin.MarkNoShowRequest;
import com.storagehub.api.checkin.RejectCheckInRequest;
import com.storagehub.api.checkin.ScheduleCheckInRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.FileAsset;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitAssignment;
import com.storagehub.domain.model.UnitAssignmentStatus;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitReleaseDisposition;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.CheckInRepository;
import com.storagehub.domain.repo.FileAssetRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.ReservationRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UnitAssignmentRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
class CheckInHandoverServiceTests {

    @Mock private ReservationRepository reservationRepository;
    @Mock private StorageUnitRepository storageUnitRepository;
    @Mock private UnitAssignmentRepository assignmentRepository;
    @Mock private CheckInRepository checkInRepository;
    @Mock private FileAssetRepository fileAssetRepository;
    @Mock private RentalRepository rentalRepository;
    @Mock private UserRepository userRepository;
    @Mock private AdminAuthorizationService authorizationService;
    @Mock private FacilityScopeService facilityScopeService;
    @Mock private AuditLogService auditLogService;
    @Mock private NotificationService notificationService;

    private CheckInHandoverService service;
    private ActorPrincipal actor;
    private User staff;
    private Reservation reservation;
    private StorageUnit unit;
    private UnitAssignment assignment;
    private CheckIn checkIn;

    @BeforeEach
    void setUp() {
        service = new CheckInHandoverService(
            reservationRepository, storageUnitRepository, assignmentRepository,
            checkInRepository, fileAssetRepository, rentalRepository, userRepository, authorizationService,
            facilityScopeService, auditLogService, notificationService, new ObjectMapper()
        );
        staff = entityWithId(new User());
        staff.setFullName("Staff A");
        actor = new ActorPrincipal(
            staff.getId(), UUID.randomUUID(), Set.of(RoleCode.STAFF),
            Set.of("view_checkins", "perform_checkin"),
            Map.of(UUID.randomUUID(), FacilityScopeLevel.OPERATE)
        );
        Facility facility = entityWithId(new Facility());
        facility.setName("Facility A");
        UnitType unitType = entityWithId(new UnitType());
        unitType.setLengthM(new BigDecimal("3"));
        unitType.setWidthM(new BigDecimal("3"));
        unitType.setHeightM(new BigDecimal("3"));
        unitType.setMaxLoadKg(new BigDecimal("1000"));
        User customer = entityWithId(new User());
        customer.setFullName("Customer A");
        customer.setEmail("customer@example.com");

        unit = entityWithId(new StorageUnit());
        unit.setFacility(facility);
        unit.setUnitType(unitType);
        unit.setCode("S001");
        unit.setStatus(StorageUnitStatus.reserved);

        reservation = entityWithId(new Reservation());
        reservation.setReservationCode("RSV-001");
        reservation.setCustomer(customer);
        reservation.setFacility(facility);
        reservation.setUnitType(unitType);
        reservation.setAssignedUnit(unit);
        reservation.setStatus(ReservationStatus.UNIT_RESERVED);
        reservation.setStartDate(LocalDate.now());
        reservation.setEndDate(LocalDate.now().plusMonths(1));
        reservation.setTotalGoodsWeightKg(new BigDecimal("100"));
        reservation.setTotalGoodsVolumeM3(BigDecimal.ONE);

        assignment = entityWithId(new UnitAssignment());
        assignment.setReservation(reservation);
        assignment.setStorageUnit(unit);
        assignment.setAssignedBy(staff);
        assignment.setAssignedAt(Instant.now().minusSeconds(3600));
        assignment.setStatus(UnitAssignmentStatus.ACTIVE);

        checkIn = entityWithId(new CheckIn());
        checkIn.setReservation(reservation);
        checkIn.setPerformedBy(staff);
        checkIn.setStatus(CheckInStatus.scheduled);
        checkIn.setScheduledAt(Instant.now().minusSeconds(60));
    }

    @Test
    void schedulesCheckInAndMarksReservationReady() {
        when(reservationRepository.findByIdForUpdate(reservation.getId()))
            .thenReturn(Optional.of(reservation));
        when(assignmentRepository.findByReservation_IdAndStatus(
            reservation.getId(), UnitAssignmentStatus.ACTIVE
        )).thenReturn(Optional.of(assignment));
        when(storageUnitRepository.findByIdForUpdate(unit.getId())).thenReturn(Optional.of(unit));
        when(checkInRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.empty());
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(checkInRepository.saveAndFlush(any(CheckIn.class))).thenAnswer(invocation -> {
            CheckIn saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });

        var response = service.schedule(
            actor, reservation.getId(),
            new ScheduleCheckInRequest(
                Instant.now().plusSeconds(3600), true, true, "Hồ sơ đã đủ"
            )
        );

        assertThat(response.checkInStatus()).isEqualTo(CheckInStatus.scheduled);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.READY_FOR_CHECKIN);
        verify(reservationRepository).saveAndFlush(reservation);
    }

    @Test
    void completesPhysicalHandoverAtTramBoundary() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        mockCompletionChecks(true);

        var response = service.complete(actor, checkIn.getId(), validCompletionRequest());

        assertThat(response.checkInStatus()).isEqualTo(CheckInStatus.completed);
        assertThat(response.reservationStatus()).isEqualTo(ReservationStatus.AWAITING_CUSTOMER_RECEIPT);
        assertThat(response.assignmentStatus()).isEqualTo(UnitAssignmentStatus.COMPLETED);
        assertThat(response.storageUnitStatus()).isEqualTo(StorageUnitStatus.assigned);
        assertThat(response.handover()).isNotNull();
        verify(rentalRepository, never()).save(any());
    }

    @Test
    void rejectsHandoverWhenGoodsExceedUnitCapacity() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        mockCompletionChecks(false);
        CompleteCheckInRequest base = validCompletionRequest();
        CompleteCheckInRequest oversized = new CompleteCheckInRequest(
            base.checklist(),
            new CheckInMeasurements(
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("1001"), BigDecimal.ONE, false
            ),
            base.varianceReason(), base.initialUnitCondition(), base.goodsCondition(), base.packageCount(),
            base.goodsCategory(), base.evidenceReferences(), base.handedOverItems(), base.notes()
        );

        assertThatThrownBy(() -> service.complete(actor, checkIn.getId(), oversized))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("weight exceeds");

        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.scheduled);
        assertThat(assignment.getStatus()).isEqualTo(UnitAssignmentStatus.ACTIVE);
    }

    @Test
    void marksPastAppointmentAsNoShowWithoutCancellingReservation() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId()))
            .thenReturn(Optional.of(reservation));
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(checkInRepository.saveAndFlush(checkIn)).thenReturn(checkIn);
        when(assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservation.getId()))
            .thenReturn(Optional.of(assignment));

        var response = service.markNoShow(
            actor, checkIn.getId(), new MarkNoShowRequest("Khách không đến")
        );

        assertThat(response.checkInStatus()).isEqualTo(CheckInStatus.no_show);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.READY_FOR_CHECKIN);
        assertThat(assignment.getStatus()).isEqualTo(UnitAssignmentStatus.ACTIVE);
    }

    @Test
    void requiresAcceptedAndExplainedVariance() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        mockCompletionChecks(false);
        CompleteCheckInRequest base = validCompletionRequest();
        CompleteCheckInRequest changed = new CompleteCheckInRequest(
            base.checklist(),
            new CheckInMeasurements(
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("120"), new BigDecimal("1.2"), false
            ),
            null, base.initialUnitCondition(), base.goodsCondition(), base.packageCount(),
            base.goodsCategory(), base.evidenceReferences(), base.handedOverItems(), base.notes()
        );

        assertThatThrownBy(() -> service.complete(actor, checkIn.getId(), changed))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("variance is not accepted");
    }

    @Test
    void rejectsCheckInAndReleasesUnitWithoutCreatingRental() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        mockCompletionChecks(false);
        UUID evidenceId = UUID.fromString("0d29e3dd-67a8-4c8c-bccd-d54e374f50b0");
        FileAsset evidence = entityWithId(new FileAsset());
        ReflectionTestUtils.setField(evidence, "id", evidenceId);
        evidence.setEntityType("CHECK_IN");
        evidence.setEntityId(checkIn.getId());
        when(fileAssetRepository.findAllById(List.of(evidenceId))).thenReturn(List.of(evidence));
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(checkInRepository.saveAndFlush(checkIn)).thenReturn(checkIn);
        when(assignmentRepository.saveAndFlush(assignment)).thenReturn(assignment);

        var response = service.reject(
            actor, checkIn.getId(),
            new RejectCheckInRequest(
                "Hàng thực tế không đúng khai báo", UnitReleaseDisposition.MAINTENANCE,
                List.of(evidenceId.toString())
            )
        );

        assertThat(response.checkInStatus()).isEqualTo(CheckInStatus.rejected);
        assertThat(response.reservationStatus()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(response.assignmentStatus()).isEqualTo(UnitAssignmentStatus.CANCELLED);
        assertThat(response.storageUnitStatus()).isEqualTo(StorageUnitStatus.maintenance);
        assertThat(reservation.getAssignedUnit()).isNull();
        verify(rentalRepository, never()).save(any());
    }

    private void mockCompletionChecks(boolean includeMutationStubs) {
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId()))
            .thenReturn(Optional.of(reservation));
        when(assignmentRepository.findByReservation_IdAndStatus(
            reservation.getId(), UnitAssignmentStatus.ACTIVE
        )).thenReturn(Optional.of(assignment));
        when(storageUnitRepository.findByIdForUpdate(unit.getId())).thenReturn(Optional.of(unit));
        when(rentalRepository.existsActiveRental(
            reservation.getId(), unit.getId(), RentalStatus.active
        )).thenReturn(false);
        if (includeMutationStubs) {
            UUID evidenceId = UUID.fromString(validCompletionRequest().evidenceReferences().getFirst());
            FileAsset evidence = entityWithId(new FileAsset());
            ReflectionTestUtils.setField(evidence, "id", evidenceId);
            evidence.setEntityType("CHECK_IN");
            evidence.setEntityId(checkIn.getId());
            when(fileAssetRepository.findAllById(List.of(evidenceId))).thenReturn(List.of(evidence));
            when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
            when(checkInRepository.saveAndFlush(checkIn)).thenReturn(checkIn);
            when(assignmentRepository.saveAndFlush(assignment)).thenReturn(assignment);
        }
    }

    private CompleteCheckInRequest validCompletionRequest() {
        return new CompleteCheckInRequest(
            new CheckInChecklist(true, true, true, true, true, true, true, true),
            new CheckInMeasurements(
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("100"), BigDecimal.ONE, true
            ),
            null,
            "Kho sạch, không hư hại", "Hàng nguyên vẹn", 3, "Đồ gia dụng",
            List.of("0d29e3dd-67a8-4c8c-bccd-d54e374f50b0"), List.of("PIN", "Biên nhận"), "Đã bàn giao"
        );
    }

    private <T> T entityWithId(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }
}
