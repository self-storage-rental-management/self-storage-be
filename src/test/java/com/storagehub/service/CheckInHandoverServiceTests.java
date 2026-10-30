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
import com.storagehub.api.checkin.VerifyCheckInIdentityRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.FileAsset;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationGoodsItem;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.Role;
import com.storagehub.domain.model.UserStatus;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitAssignment;
import com.storagehub.domain.model.UnitAssignmentStatus;
import com.storagehub.domain.model.UnitType;
import com.storagehub.domain.model.UnitReleaseDisposition;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.CheckInRepository;
import com.storagehub.domain.repo.FileAssetRepository;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.domain.repo.MaintenanceTaskRepository;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.ReservationGoodsItemRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
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
    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentProvenance paymentProvenance;
    @Mock private com.storagehub.service.ledger.SandboxPaymentRecognitionPolicy sandboxPaymentPolicy;
    @Mock private MaintenanceTaskRepository maintenanceTaskRepository;
    @Mock private ReservationGoodsItemRepository goodsItemRepository;
    @Mock private ReservationPricingSnapshotRepository pricingSnapshotRepository;
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
    private com.storagehub.domain.model.Payment depositPayment;

    @BeforeEach
    void setUp() {
        service = new CheckInHandoverService(
            reservationRepository, storageUnitRepository, assignmentRepository,
            checkInRepository, fileAssetRepository, rentalRepository, paymentRepository,
            maintenanceTaskRepository,
            goodsItemRepository, pricingSnapshotRepository, userRepository, authorizationService,
            facilityScopeService, auditLogService, notificationService, new ObjectMapper()
        );
        ReflectionTestUtils.setField(service,"paymentProvenance",paymentProvenance);
        ReflectionTestUtils.setField(service,"sandboxPaymentPolicy",sandboxPaymentPolicy);
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
        reservation.setIdentityDocumentNumber("079203009988");
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
        paidReservationFixture();
    }

    @Test
    void schedulesCheckInAndMarksReservationReady() {
        var staffRole = new Role();
        staffRole.setCode(RoleCode.STAFF);
        staff.setStatus(UserStatus.ACTIVE);
        staff.getRoles().add(staffRole);
        when(userRepository.findEligibleStaff(
            UserStatus.ACTIVE, RoleCode.STAFF, reservation.getFacility().getId(),
            List.of(FacilityScopeLevel.OPERATE, FacilityScopeLevel.MANAGE)
        )).thenReturn(List.of(staff));
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
        assertThat(response.identityDocumentNumber()).isEqualTo("079203009988");
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
    void persistsIdentityVerificationAsTheFirstWorkflowStep() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(checkInRepository.saveAndFlush(checkIn)).thenReturn(checkIn);
        when(assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservation.getId()))
            .thenReturn(Optional.of(assignment));

        var response = service.verifyIdentity(
            actor, checkIn.getId(), new VerifyCheckInIdentityRequest("0792 0300 9988", "Đã xem giấy tờ gốc")
        );

        assertThat(response.checkInStatus()).isEqualTo(CheckInStatus.identity_verified);
        assertThat(checkIn.getVerifiedIdentityDocument()).isEqualTo("079203009988");
        assertThat(checkIn.getIdentityVerifiedAt()).isNotNull();
    }

    @Test
    void preventsInventoryVerificationBeforeCustomerSetupIsCompleted() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.complete(actor, checkIn.getId(), validCompletionRequest()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("setup must be completed");
    }

    @Test
    void allowsInventoryVerificationAfterEarlySetupCompletion() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        mockCompletionChecks(true);
        Rental rental = checkIn.getRental();
        LocalDate paidRentalStart = LocalDate.now().plusDays(3);
        rental.setStatus(RentalStatus.setup);
        rental.setSetupEndsAt(Instant.now().plusSeconds(3600));
        rental.setStartDate(paidRentalStart);
        rental.setContractEndDate(paidRentalStart.plusMonths(1));

        var response = service.complete(actor, checkIn.getId(), validCompletionRequest());

        assertThat(response.checkInStatus()).isEqualTo(CheckInStatus.completed);
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.setup);
        assertThat(rental.getStartDate()).isEqualTo(paidRentalStart);
    }

    @Test
    void rejectsMismatchedIdentityWithoutVerificationNote() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        mockCompletionChecks(false);

        CompleteCheckInRequest request = completionRequest("079203009999", "");

        assertThatThrownBy(() -> service.complete(actor, checkIn.getId(), request))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("identity does not match");
        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.setup_completed);
    }

    @Test
    void rejectsMismatchedIdentityEvenWhenVerificationNoteIsRecorded() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        mockCompletionChecks(false);

        assertThatThrownBy(() -> service.complete(
            actor, checkIn.getId(), completionRequest("079203009999", "Tên trên giấy tờ không trùng với hồ sơ")
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("identity does not match");
        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.setup_completed);
    }

    @Test
    void rejectsLegacyReservationWithoutDeclaredIdentity() {
        reservation.setIdentityDocumentNumber(null);
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        mockCompletionChecks(false);

        assertThatThrownBy(() -> service.complete(actor, checkIn.getId(), validCompletionRequest()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("identity does not match");
        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.setup_completed);
    }

    @Test
    void rejectsHandoverWhenGoodsExceedUnitCapacity() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        mockCompletionChecks(false);
        CompleteCheckInRequest base = validCompletionRequest();
        CompleteCheckInRequest oversized = new CompleteCheckInRequest(
            base.checklist(),
            base.identityDocumentNumber(),
            base.identityVerificationNote(),
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

        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.setup_completed);
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
            base.identityDocumentNumber(),
            base.identityVerificationNote(),
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
    void requiresAcceptedAndExplainedPackageCountDifference() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        mockCompletionChecks(false);
        ReservationGoodsItem declaredItem = entityWithId(new ReservationGoodsItem());
        declaredItem.setQuantity(3);
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId()))
            .thenReturn(List.of(declaredItem));
        CompleteCheckInRequest base = validCompletionRequest();
        CompleteCheckInRequest changed = new CompleteCheckInRequest(
            base.checklist(), base.identityDocumentNumber(), base.identityVerificationNote(), new CheckInMeasurements(
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("100"), BigDecimal.ONE, false
            ), null, base.initialUnitCondition(),
            base.goodsCondition(), 2, base.goodsCategory(), base.evidenceReferences(),
            base.handedOverItems(), base.notes()
        );

        assertThatThrownBy(() -> service.complete(actor, checkIn.getId(), changed))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("variance is not accepted");
    }

    @Test
    void requiresAcceptedAndExplainedDeclaredDimensionDifference() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        mockCompletionChecks(false);
        ReservationGoodsItem declaredItem = entityWithId(new ReservationGoodsItem());
        declaredItem.setQuantity(12);
        declaredItem.setLengthCm(new BigDecimal("12"));
        declaredItem.setWidthCm(new BigDecimal("12"));
        declaredItem.setHeightCm(new BigDecimal("12"));
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId()))
            .thenReturn(List.of(declaredItem));
        CompleteCheckInRequest base = validCompletionRequest();
        CompleteCheckInRequest changed = new CompleteCheckInRequest(
            base.checklist(), base.identityDocumentNumber(), base.identityVerificationNote(), new CheckInMeasurements(
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("100"), BigDecimal.ONE, false
            ), null, base.initialUnitCondition(), base.goodsCondition(), base.packageCount(),
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
        checkIn.setStatus(CheckInStatus.scheduled);
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"MISSING_PROOF","WRONG_AMOUNT","REFUNDED","NO_PAYMENT","MISSING_SNAPSHOT"})
    void invalidPrepaymentCannotVerifyIdentity(String kind) {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        switch(kind) {
            case "MISSING_PROOF" -> when(paymentProvenance.classify(depositPayment)).thenReturn(PaymentProvenance.Classification.UNVERIFIED);
            case "WRONG_AMOUNT" -> depositPayment.setAmount(depositPayment.getAmount().add(BigDecimal.ONE));
            case "REFUNDED" -> depositPayment.setRefundedAmount(BigDecimal.ONE);
            case "NO_PAYMENT" -> when(paymentRepository.findAllByReservation_IdAndStatusIn(reservation.getId(),List.of(com.storagehub.domain.model.PaymentStatus.PAID))).thenReturn(List.of());
            case "MISSING_SNAPSHOT" -> when(pricingSnapshotRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.empty());
        }
        assertThatThrownBy(()->service.verifyIdentity(actor,checkIn.getId(),new VerifyCheckInIdentityRequest("079203009988","TEST")))
            .isInstanceOf(ApiException.class);
        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.scheduled);
        verify(checkInRepository,never()).saveAndFlush(any());
        verify(rentalRepository,never()).save(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "2026-10-30T03:00:00Z,6,2026-11-05,2026-12-05",
        "2026-10-26T03:00:00Z,3,2026-10-29,2026-11-29",
        "2026-10-31T16:59:59Z,3,2026-11-03,2026-12-03",
        "2026-10-31T17:00:00Z,3,2026-11-04,2026-12-04",
        "2026-12-31T16:59:59Z,0,2026-12-31,2027-01-31",
        "2026-12-31T17:00:00Z,0,2027-01-01,2027-02-01",
        "2026-01-28T03:00:00Z,3,2026-01-31,2026-02-28"
    })
    void actualHandoverDeterminesPaidPeriodFromAcceptedSetupSnapshot(String instant,int days,String expectedStart,String expectedEnd) {
        var at=Instant.parse(instant);var start=LocalDate.parse(expectedStart);var end=LocalDate.parse(expectedEnd);
        var snapshot=pricingSnapshotRepository.findByReservation_Id(reservation.getId()).orElseThrow();
        snapshot.setFreeSetupDays(days);reservation.getSourceQuote().setFreeSetupDays(days);reservation.setSetupDaysSnapshot(days);
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);checkIn.setStatus(CheckInStatus.payment_collected);
        when(assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservation.getId())).thenReturn(Optional.of(assignment));
        // Deliberately different planned dates and live catalog price must not set the paid period or price.
        reservation.setStartDate(LocalDate.of(2025,1,1));reservation.setEndDate(LocalDate.of(2025,2,1));
        unit.getUnitType().setMonthlyPrice(new BigDecimal("999999"));
        var balance=entityWithId(new com.storagehub.domain.model.Payment());balance.setReservation(reservation);
        balance.setPurpose(com.storagehub.domain.model.PaymentType.RENTAL_BALANCE);
        balance.setStatus(com.storagehub.domain.model.PaymentStatus.PAID);balance.setAmount(snapshot.getDueAtCheckIn());
        when(paymentRepository.findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(reservation.getId(),com.storagehub.domain.model.PaymentType.RENTAL_BALANCE)).thenReturn(Optional.of(balance));
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(rentalRepository.saveAndFlush(any(Rental.class))).thenAnswer(call->{Rental r=call.getArgument(0);ReflectionTestUtils.setField(r,"id",UUID.randomUUID());return r;});
        when(checkInRepository.saveAndFlush(checkIn)).thenReturn(checkIn);
        try(var time=org.mockito.Mockito.mockStatic(Instant.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(at);
            service.startSetup(actor,checkIn.getId(),new com.storagehub.api.checkin.StartCheckInSetupRequest("TEST-LOCK","TEST handover"));
        }
        var rental=checkIn.getRental();
        assertThat(rental.getSetupStartedAt()).isEqualTo(at);
        assertThat(rental.getStartDate()).isEqualTo(start);assertThat(rental.getContractEndDate()).isEqualTo(end);
        assertThat(rental.getSetupEndsAt()).isEqualTo(start.atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant());
        assertThat(rental.getMonthlyPrice()).isEqualByComparingTo(snapshot.getMonthlyPrice());
        assertThat(reservation.getStartDate()).isEqualTo(start);
    }

    private void paidReservationFixture() {
        var quote=entityWithId(new com.storagehub.domain.model.ReservationQuote());
        quote.setPolicyVersion("TEST-V1");quote.setPricingPackageCode("TEST-1M");
        quote.setRentalMonths(1);quote.setFreeSetupDays(0);quote.setQuotedAt(Instant.parse("2026-10-01T00:00:00Z"));
        quote.setUnitTypeSnapshotJson("{\"name\":\"TEST\",\"lengthM\":3,\"widthM\":3,\"heightM\":3,\"maxLoadKg\":1000,\"rackCount\":0}");
        quote.setMonthlyPrice(new BigDecimal("100"));quote.setSubtotal(new BigDecimal("100"));
        quote.setDiscountRate(BigDecimal.ZERO);quote.setDiscountAmount(BigDecimal.ZERO);quote.setTotalAfterDiscount(new BigDecimal("100"));
        quote.setReservationDepositAmount(new BigDecimal("20"));quote.setSecurityDepositAmount(new BigDecimal("100"));
        quote.setRemainingRentalAmount(new BigDecimal("80"));quote.setDueAtCheckIn(new BigDecimal("180"));quote.setTotalInitialObligation(new BigDecimal("200"));
        reservation.setSourceQuote(quote);reservation.setSetupDaysSnapshot(0);
        var snapshot=new com.storagehub.domain.model.ReservationPricingSnapshot();snapshot.setReservation(reservation);
        snapshot.setPricingPolicyVersion(quote.getPolicyVersion());snapshot.setPricingPackageCode(quote.getPricingPackageCode());
        snapshot.setRentalMonths(quote.getRentalMonths());snapshot.setFreeSetupDays(quote.getFreeSetupDays());snapshot.setQuotedAt(quote.getQuotedAt());
        snapshot.setMonthlyPrice(quote.getMonthlyPrice());snapshot.setGrossRentalAmount(quote.getSubtotal());
        snapshot.setDiscountRate(quote.getDiscountRate());snapshot.setDiscountAmount(quote.getDiscountAmount());snapshot.setNetRentalAmount(quote.getTotalAfterDiscount());
        snapshot.setReservationDepositAmount(quote.getReservationDepositAmount());snapshot.setSecurityDepositAmount(quote.getSecurityDepositAmount());
        snapshot.setRemainingRentalAmount(quote.getRemainingRentalAmount());snapshot.setDueAtCheckIn(quote.getDueAtCheckIn());snapshot.setTotalInitialObligation(quote.getTotalInitialObligation());
        var payment=entityWithId(new com.storagehub.domain.model.Payment());payment.setReservation(reservation);
        payment.setPurpose(com.storagehub.domain.model.PaymentType.RESERVATION_DEPOSIT);payment.setStatus(com.storagehub.domain.model.PaymentStatus.PAID);
        depositPayment=payment;
        payment.setAmount(quote.getReservationDepositAmount());payment.setRefundedAmount(BigDecimal.ZERO);
        org.mockito.Mockito.lenient().when(pricingSnapshotRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(snapshot));
        org.mockito.Mockito.lenient().when(paymentRepository.findAllByReservation_IdAndStatusIn(reservation.getId(),List.of(com.storagehub.domain.model.PaymentStatus.PAID))).thenReturn(List.of(payment));
        org.mockito.Mockito.lenient().when(paymentProvenance.classify(payment)).thenReturn(PaymentProvenance.Classification.VERIFIED);
    }

    private void mockCompletionChecks(boolean includeMutationStubs) {
        checkIn.setStatus(CheckInStatus.setup_completed);
        checkIn.setLockReference("LEGACY-LOCK");
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId()))
            .thenReturn(Optional.of(reservation));
        Rental rental = entityWithId(new Rental());
        rental.setReservation(reservation);
        rental.setCustomer(reservation.getCustomer());
        rental.setFacility(reservation.getFacility());
        rental.setStorageUnit(unit);
        rental.setStatus(RentalStatus.active);
        rental.setSetupEndsAt(Instant.now().minusSeconds(1));
        checkIn.setRental(rental);
        org.mockito.Mockito.lenient().when(rentalRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(rental));
        when(assignmentRepository.findByReservation_IdAndStatus(
            reservation.getId(), UnitAssignmentStatus.ACTIVE
        )).thenReturn(Optional.of(assignment));
        when(storageUnitRepository.findByIdForUpdate(unit.getId())).thenReturn(Optional.of(unit));
        if (includeMutationStubs) {
            var balance=entityWithId(new com.storagehub.domain.model.Payment());balance.setReservation(reservation);
            balance.setPurpose(com.storagehub.domain.model.PaymentType.RENTAL_BALANCE);
            balance.setStatus(com.storagehub.domain.model.PaymentStatus.PAID);balance.setAmount(new BigDecimal("180"));
            when(paymentRepository.findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(reservation.getId(),com.storagehub.domain.model.PaymentType.RENTAL_BALANCE)).thenReturn(Optional.of(balance));
            when(rentalRepository.saveAndFlush(rental)).thenReturn(rental);
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
        return completionRequest("079203009988", "Đã bàn giao");
    }

    private CompleteCheckInRequest completionRequest(String identityDocumentNumber, String notes) {
        return new CompleteCheckInRequest(
            new CheckInChecklist(true, true, true, true, true, true, true, true),
            identityDocumentNumber,
            notes.isBlank() ? null : notes,
            new CheckInMeasurements(
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("100"), BigDecimal.ONE, true
            ),
            null,
            "Kho sạch, không hư hại", "Hàng nguyên vẹn", 3, "Đồ gia dụng",
            List.of("0d29e3dd-67a8-4c8c-bccd-d54e374f50b0"), List.of("Ổ khóa LEGACY-LOCK", "Biên nhận"), notes
        );
    }

    private <T> T entityWithId(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }
}
