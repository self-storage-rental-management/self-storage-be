package com.storagehub.service;

import com.storagehub.domain.model.SystemPermission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.checkin.CheckInChecklist;
import com.storagehub.api.checkin.CheckInMeasurements;
import com.storagehub.api.checkin.CompleteCheckInRequest;
import com.storagehub.api.checkin.RejectCheckInRequest;
import com.storagehub.api.checkin.ScheduleCheckInRequest;
import com.storagehub.api.checkin.VerifyCheckInIdentityRequest;
import com.storagehub.api.checkin.VerifyInventoryIdentityRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.CheckIn;
import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.FileAsset;
import com.storagehub.domain.model.FileAssetStatus;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationGoodsItem;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.NotificationType;
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
    @Mock private com.storagehub.domain.repo.ReturnCaseRepository returnCaseRepository;
    @Mock private UserRepository userRepository;
    @Mock private AdminAuthorizationService authorizationService;
    @Mock private FacilityScopeService facilityScopeService;
    @Mock private AuditLogService auditLogService;
    @Mock private NotificationService notificationService;

    private CheckInHandoverService service;
    private ActorPrincipal actor;
    private ActorPrincipal managerActor;
    private User staff;
    private User manager;
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
            facilityScopeService, auditLogService, notificationService, new ObjectMapper().findAndRegisterModules()
        );
        ReflectionTestUtils.setField(service,"paymentProvenance",paymentProvenance);
        ReflectionTestUtils.setField(service,"sandboxPaymentPolicy",sandboxPaymentPolicy);
        ReflectionTestUtils.setField(service,"inventoryReturnCases",returnCaseRepository);
        staff = entityWithId(new User());
        staff.setFullName("Staff A");
        manager = entityWithId(new User());
        manager.setFullName("Manager A");
        Facility facility = entityWithId(new Facility());
        actor = new ActorPrincipal(
            staff.getId(), UUID.randomUUID(), Set.of(RoleCode.STAFF),
            Set.of(SystemPermission.VIEW_CHECKINS.code(), SystemPermission.PERFORM_CHECKIN.code()),
            Map.of(facility.getId(), FacilityScopeLevel.OPERATE)
        );
        managerActor = new ActorPrincipal(
            manager.getId(), UUID.randomUUID(), Set.of(RoleCode.MANAGER),
            Set.of(SystemPermission.VIEW_CHECKINS.code(), SystemPermission.MANAGE_RENTALS.code()),
            Map.of(facility.getId(), FacilityScopeLevel.MANAGE)
        );
        facility.setName("Facility A");
        UnitType unitType = entityWithId(new UnitType());
        unitType.setCode("L");
        unitType.setLengthM(new BigDecimal("3"));
        unitType.setWidthM(new BigDecimal("3"));
        unitType.setHeightM(new BigDecimal("3"));
        unitType.setMaxLoadKg(new BigDecimal("1000"));
        User customer = entityWithId(new User());
        customer.setFullName("Customer A");
        customer.setEmail("customer@example.com");
        customer.setPhone("0912345678");
        customer.setPermanentAddress("123 Duong Le Loi, Q1, TP HCM");
        customer.setEmergencyContactName("Nguoi Than");
        customer.setEmergencyContactPhone("0987654321");
        customer.getProfileDetails().setEmergencyContactRelationship("Bo me");
        customer.getProfileDetails().setEmergencyContactConsent(true);

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
        org.mockito.Mockito.lenient().when(assignmentRepository.findByReservation_IdAndStatus(
            reservation.getId(), UnitAssignmentStatus.ACTIVE)).thenReturn(Optional.of(assignment));
        org.mockito.Mockito.lenient().when(storageUnitRepository.findByIdForUpdate(unit.getId())).thenReturn(Optional.of(unit));
        org.mockito.Mockito.lenient().when(facilityScopeService.canAccessFacility(actor, facility.getId(), FacilityScopeLevel.OPERATE)).thenReturn(true);
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
        assertThat(response.intakeAction().name()).isEqualTo("START");
        assertThat(response.identityDocumentNumber()).isEqualTo("079203009988");
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.READY_FOR_CHECKIN);
        verify(reservationRepository).saveAndFlush(reservation);
    }

    @Test
    void listProjectsRecoveryTransferEvenWhenLegacyCheckInHasNoRentalLink() {
        checkIn.setStatus(CheckInStatus.setup_completed);
        var rental = entityWithId(new Rental());
        rental.setInventoryEscalatedAt(Instant.now());
        when(rentalRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(rental));
        var row = mock(ReservationRepository.CheckInWorkRow.class);
        when(row.getReservation()).thenReturn(reservation);
        when(row.getCheckIn()).thenReturn(checkIn);
        when(row.getAssignment()).thenReturn(assignment);
        when(reservationRepository.findCheckInWorkOrdered(eq("PROCESSED"), any(), any(), any(), any(),
            isNull(), isNull(), eq(true), eq(false), eq(true), isNull(), isNull(), isNull(),
            eq(false), any(), eq("priority"), isNull(), any())).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(row)));

        var result = service.list(actor, null, null, "RECOVERY_TRANSFERRED",
            CheckInHandoverService.WorkGroup.PROCESSED, null, null, 0, 20, "test");

        assertThat(result.data()).singleElement().satisfies(response -> {
            assertThat(response.checkInStatus()).isEqualTo(CheckInStatus.setup_completed);
            assertThat(response.inventoryVisit().access().recoveryTransferred()).isTrue();
            assertThat(response.inventoryVisit().access().writable()).isFalse();
            assertThat(response.intakeAction().name()).isEqualTo("NONE");
        });
    }

    @Test
    void inventoryDraftOpensEmptyAndRejectsStaleSavesAndPublication() {
        mockCompletionChecks(false);
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        reservation.setStatus(ReservationStatus.PREPARING);
        UUID id = checkIn.getId();
        assertThat(service.inventoryDraft(actor, id).revision()).isZero();
        var request = validCompletionRequest();
        var saved = service.saveInventoryDraft(actor, id, request, 0);
        assertThat(service.inventoryDraft(actor, id)).isEqualTo(saved);
        assertThatThrownBy(() -> service.saveInventoryDraft(actor, id, request, 0)).hasMessageContaining("đã thay đổi");
        assertThatThrownBy(() -> service.publishInventoryDraft(actor, id, 0)).hasMessageContaining("đã thay đổi");
    }

    @Test
    void completesInventoryAndOccupiesAssignedUnit() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        mockCompletionChecks(true);
        var first = entityWithId(new ReservationGoodsItem());
        first.setQuantity(1);
        var second = entityWithId(new ReservationGoodsItem());
        second.setQuantity(2);
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId()))
            .thenReturn(List.of(first, second));

        var base = validCompletionRequest();
        var firstMeasurements = new CompleteCheckInRequest.InventoryMeasurements(
            new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("25"), new BigDecimal("60"));
        var secondMeasurements = new CompleteCheckInRequest.InventoryMeasurements(
            new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("40"));
        var lines = List.of(
            new CompleteCheckInRequest.InventoryLine(first.getId(), 2, CompleteCheckInRequest.InventoryLine.Condition.INTACT,
                "Thừa một kiện", firstMeasurements),
            new CompleteCheckInRequest.InventoryLine(second.getId(), 1, CompleteCheckInRequest.InventoryLine.Condition.INTACT,
                "Thiếu một kiện", secondMeasurements)
        );
        var measurements = new CheckInMeasurements(new BigDecimal("50"), new BigDecimal("100"), new BigDecimal("100"),
            new BigDecimal("100"), BigDecimal.ONE, true);
        var request = new CompleteCheckInRequest(base.checklist(), base.identityDocumentNumber(),
            base.identityVerificationNote(), measurements, base.varianceReason(), base.initialUnitCondition(),
            base.goodsCondition(), base.packageCount(), base.goodsCategory(), base.evidenceReferences(),
            base.handedOverItems(), base.notes(), base.lockReference(), lines);
        var response = service.complete(actor, checkIn.getId(), request);

        assertThat(response.checkInStatus()).isEqualTo(CheckInStatus.completed);
        assertThat(response.reservationStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(response.assignmentStatus()).isEqualTo(UnitAssignmentStatus.COMPLETED);
        assertThat(response.storageUnitStatus()).isEqualTo(StorageUnitStatus.occupied);
        assertThat(response.handover().inventoryLines()).containsExactlyElementsOf(lines);
        verify(rentalRepository).saveAndFlush(checkIn.getRental());
    }

    @Test
    void acceptsLegacyAggregateInventoryWhileFrontendIsRollingOut() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        mockCompletionChecks(true);
        var declared = entityWithId(new ReservationGoodsItem());
        declared.setQuantity(3);
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId()))
            .thenReturn(List.of(declared));

        var response = service.complete(actor, checkIn.getId(), validCompletionRequest());

        assertThat(response.checkInStatus()).isEqualTo(CheckInStatus.completed);
        assertThat(response.handover().inventoryLines()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void comparesActualCatalogPerLineAndKeepsOriginalDeclaration(boolean prohibited) throws Exception {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN); mockCompletionChecks(true);
        var rental=checkIn.getRental(); rental.setSetupCompletionDeadlineAt(Instant.now().plusSeconds(86400));
        rental.setInventoryArrivedAt(Instant.now()); rental.setInventoryIdentityVerifiedAt(Instant.now());
        var original=entityWithId(new ReservationGoodsItem()); original.setQuantity(3);
        original.setCategory(com.storagehub.domain.model.GoodsCategory.KITCHENWARE);
        original.setCatalogCategoryId(UUID.randomUUID());original.setCatalogMaterialId(UUID.randomUUID());
        original.setCategoryName("Đồ gia dụng");original.setMaterialName("Gỗ");
        original.setLengthCm(new BigDecimal("100"));original.setWidthCm(new BigDecimal("100"));original.setHeightCm(new BigDecimal("100"));
        original.setWeightKg(new BigDecimal("33.333333"));
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId())).thenReturn(List.of(original));
        var actual=new com.storagehub.api.reservation.GoodsItemRequest();
        actual.setCategory(com.storagehub.domain.model.GoodsCategory.KITCHENWARE);actual.setCatalogCategoryId(original.getCatalogCategoryId());
        actual.setCatalogMaterialId(UUID.randomUUID());actual.setCategoryName("Nhãn chưa đối soát");actual.setMaterialName("Vật liệu thực tế");
        actual.setStackable(false);actual.setAllowHorizontalPlacement(true);actual.setMaxStackLayers(1);
        var catalog=org.mockito.Mockito.mock(GoodsCatalogService.class);ReflectionTestUtils.setField(service,"goodsCatalogService",catalog);
        if(prohibited) org.mockito.Mockito.doThrow(com.storagehub.common.api.ApiExceptions.validation("Vật liệu không được nhận",null)).when(catalog).validateForUnitType(any(),any(com.storagehub.api.reservation.GoodsItemRequest.class),org.mockito.ArgumentMatchers.anyInt());
        else {
            org.mockito.Mockito.doAnswer(call->{var g=(com.storagehub.api.reservation.GoodsItemRequest)call.getArgument(1);g.setCategoryName("Đồ gia dụng");g.setMaterialName("Kim loại");return null;}).when(catalog).validateForUnitType(any(),any(com.storagehub.api.reservation.GoodsItemRequest.class),org.mockito.ArgumentMatchers.anyInt());
            var packing=org.mockito.Mockito.mock(PalletInventoryService.class);ReflectionTestUtils.setField(service,"palletInventory",packing);
            when(packing.evaluateActualFit(eq(unit),any())).thenReturn(new PalletInventoryService.Fit(org.mockito.Mockito.mock(com.storagehub.api.reservation.PalletPlan.class),1,unit,List.of(),false));
        }
        var base=validCompletionRequest();
        var line=new CompleteCheckInRequest.InventoryLine(original.getId(),3,CompleteCheckInRequest.InventoryLine.Condition.INTACT,"Đã đối chiếu chất liệu",new CompleteCheckInRequest.InventoryMeasurements(new BigDecimal("100"),new BigDecimal("100"),new BigDecimal("100"),new BigDecimal("100")),actual);
        var measurements=new CheckInMeasurements(new BigDecimal("100"),new BigDecimal("100"),new BigDecimal("100"),new BigDecimal("100"),new BigDecimal("3"),true);
        var request=new CompleteCheckInRequest(base.checklist(),base.identityDocumentNumber(),null,measurements,"Chất liệu và điều kiện xếp thực tế khác khai báo",base.initialUnitCondition(),base.goodsCondition(),3,"Đồ gia dụng","Gỗ",base.evidenceReferences(),base.handedOverItems(),base.notes(),base.lockReference(),List.of(line));
        var response=service.complete(actor,checkIn.getId(),request);
        assertThat(response.inventoryResolutionStatus()).isEqualTo(com.storagehub.domain.model.InventoryResolutionStatus.PENDING_CUSTOMER);
        assertThat(response.inventoryFullFit()).isEqualTo(!prohibited);
        assertThat(original.getMaterialName()).isEqualTo("Gỗ");
        assertThat(checkIn.getInventoryActualJson()).contains("actualGoods",actual.getCatalogMaterialId().toString());
        assertThat(response.handover().inventoryLines().getFirst().actualGoods().getMaterialName()).isEqualTo(prohibited?"Vật liệu thực tế":"Kim loại");
        if(prohibited) assertThat(rental.getInventoryRemovalDeadlineAt()).isNotNull();
    }

    /**
     * A fit must be decided from the real sizes. When the operator records the actual size on
     * actualGoods but leaves the per-line measurements empty, the calculator must NOT fall back
     * to the customer's declared size.
     */
    @Test
    void actualGoodsSizeWinsOverDeclaredSizeWhenMeasurementsAreAbsent() throws Exception {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN); mockCompletionChecks(true);
        var rental=checkIn.getRental(); rental.setSetupCompletionDeadlineAt(Instant.now().plusSeconds(86400));
        rental.setInventoryArrivedAt(Instant.now()); rental.setInventoryIdentityVerifiedAt(Instant.now());
        var original=entityWithId(new ReservationGoodsItem()); original.setQuantity(2);
        original.setCategory(com.storagehub.domain.model.GoodsCategory.KITCHENWARE);
        original.setCatalogCategoryId(UUID.randomUUID()); original.setCatalogMaterialId(UUID.randomUUID());
        original.setCategoryName("Đồ gia dụng"); original.setMaterialName("Gỗ");
        // Declared size is small, the real size is much larger.
        original.setLengthCm(new BigDecimal("10")); original.setWidthCm(new BigDecimal("10"));
        original.setHeightCm(new BigDecimal("10")); original.setWeightKg(new BigDecimal("5"));
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId())).thenReturn(List.of(original));

        var actual=new com.storagehub.api.reservation.GoodsItemRequest();
        actual.setCategory(com.storagehub.domain.model.GoodsCategory.KITCHENWARE);
        actual.setCatalogCategoryId(original.getCatalogCategoryId());
        actual.setCatalogMaterialId(original.getCatalogMaterialId());
        actual.setCategoryName("Đồ gia dụng"); actual.setMaterialName("Gỗ");
        actual.setStackable(true); actual.setAllowHorizontalPlacement(true); actual.setMaxStackLayers(2);
        actual.setDescription("Hàng hóa kiểm kê thực tế ghi nhận kích thước lớn hơn khai báo");
        actual.setQuantity(2);
        actual.setLengthCm(new BigDecimal("150")); actual.setWidthCm(new BigDecimal("120"));
        actual.setHeightCm(new BigDecimal("140")); actual.setWeightPerItemKg(new BigDecimal("80"));
        var catalog=org.mockito.Mockito.mock(GoodsCatalogService.class);
        ReflectionTestUtils.setField(service,"goodsCatalogService",catalog);
        var packing=org.mockito.Mockito.mock(PalletInventoryService.class);
        ReflectionTestUtils.setField(service,"palletInventory",packing);
        when(packing.evaluateActualFit(eq(unit),any())).thenReturn(
            new PalletInventoryService.Fit(null,0,unit,List.of(),false));

        // No per-line measurements: the actual size must come from actualGoods.
        var line=new CompleteCheckInRequest.InventoryLine(original.getId(),2,
            CompleteCheckInRequest.InventoryLine.Condition.INTACT,"Kích thước thực tế lớn hơn khai báo",
            null,actual);
        var base=validCompletionRequest();
        var measurements=new CheckInMeasurements(new BigDecimal("150"),new BigDecimal("120"),
            new BigDecimal("140"),new BigDecimal("160"),new BigDecimal("3"),true);
        var request=new CompleteCheckInRequest(base.checklist(),base.identityDocumentNumber(),null,measurements,
            "Kích thước thực tế lớn hơn khai báo rõ rệt",base.initialUnitCondition(),base.goodsCondition(),2,
            "Đồ gia dụng","Gỗ",base.evidenceReferences(),base.handedOverItems(),base.notes(),base.lockReference(),
            List.of(line));

        service.complete(actor,checkIn.getId(),request);

        org.mockito.ArgumentCaptor<List<com.storagehub.api.reservation.GoodsItemRequest>> captor =
            org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(packing, org.mockito.Mockito.atLeastOnce()).evaluateActualFit(eq(unit),captor.capture());
        var used=captor.getAllValues().getFirst().getFirst();
        // The real size must be used, not the declared 10x10x10.
        assertThat(used.getLengthCm()).isEqualByComparingTo("150");
        assertThat(used.getWidthCm()).isEqualByComparingTo("120");
        assertThat(used.getHeightCm()).isEqualByComparingTo("140");
        assertThat(used.getWeightPerItemKg()).isEqualByComparingTo("80");
    }

    @Test
    void persistsIdentityVerificationAsTheFirstWorkflowStep() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(userRepository.findByIdForUpdate(reservation.getCustomer().getId())).thenReturn(Optional.of(reservation.getCustomer()));
        when(checkInRepository.saveAndFlush(checkIn)).thenReturn(checkIn);
        when(assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservation.getId()))
            .thenReturn(Optional.of(assignment));

        var response = service.verifyIdentity(
            actor, checkIn.getId(), new VerifyCheckInIdentityRequest("0792 0300 9988", "Đã xem giấy tờ gốc", false, true)
        );

        assertThat(response.checkInStatus()).isEqualTo(CheckInStatus.identity_verified);
        assertThat(response.intakeAction().name()).isEqualTo("CONTINUE");
        assertThat(checkIn.getVerifiedIdentityDocument()).isEqualTo("079203009988");
        assertThat(checkIn.getIdentityVerifiedAt()).isNotNull();
    }

    @Test
    void allowsStaffToCorrectHandoverIdentityWithoutChangingCustomerAccount() {
        var profile = reservation.getCustomer().getProfileDetails();
        profile.setIdentityDocumentNumber("074204005899");
        profile.setIdentityVerifiedAt(Instant.parse("2026-01-01T00:00:00Z"));
        reservation.getCustomer().setIdentityDocumentHash(CustomerIdentityPolicy.hash("074204005899"));
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        reservation.setIdentityDocumentNumber("074204005899");
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(userRepository.findByIdForUpdate(reservation.getCustomer().getId())).thenReturn(Optional.of(reservation.getCustomer()));
        when(checkInRepository.saveAndFlush(checkIn)).thenReturn(checkIn);
        when(assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservation.getId()))
            .thenReturn(Optional.of(assignment));

        var response = service.verifyIdentity(
            actor, checkIn.getId(), new VerifyCheckInIdentityRequest("074204005212", "Đã đối chiếu giấy tờ gốc", false, true, true)
        );

        assertThat(response.checkInStatus()).isEqualTo(CheckInStatus.identity_verified);
        assertThat(checkIn.getVerifiedIdentityDocument()).isEqualTo("074204005212");
        assertThat(reservation.getIdentityDocumentNumber()).isEqualTo("074204005212");
        assertThat(profile.getIdentityDocumentNumber()).isEqualTo("074204005899");
        assertThat(profile.getIdentityVerifiedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(reservation.getCustomer().getIdentityDocumentHash()).isEqualTo(CustomerIdentityPolicy.hash("074204005899"));
        verify(userRepository, never()).saveAndFlush(reservation.getCustomer());
        assertThat(checkIn.getIdentityVerificationNote()).contains("Đã đối chiếu giấy tờ gốc");
        assertThat(checkIn.getIdentityVerifiedAt()).isNotNull();
    }

    @Test
    void correctionAfterVerificationChangesOnlyHandoverRecord() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        var customer = reservation.getCustomer();
        customer.getProfileDetails().setIdentityDocumentNumber("079203009988");
        customer.getProfileDetails().setIdentityVerifiedAt(Instant.parse("2026-01-01T00:00:00Z"));
        customer.setIdentityDocumentHash(CustomerIdentityPolicy.hash("079203009988"));
        checkIn.setStatus(CheckInStatus.identity_verified);
        checkIn.setIdentityVerifiedAt(Instant.now());
        checkIn.setVerifiedIdentityDocument("079203009988");
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(userRepository.findByIdForUpdate(customer.getId())).thenReturn(Optional.of(customer));
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservation.getId())).thenReturn(Optional.of(assignment));

        assertThat(service.checkIdentityCorrection(actor, checkIn.getId(), new VerifyCheckInIdentityRequest("079203009989", null)).valid()).isTrue();
        service.correctIdentity(actor, checkIn.getId(), new VerifyCheckInIdentityRequest("079203009989", "Đã đối chiếu giấy tờ gốc", false, true));

        assertThat(checkIn.getVerifiedIdentityDocument()).isEqualTo("079203009989");
        assertThat(reservation.getIdentityDocumentNumber()).isEqualTo("079203009989");
        assertThat(checkIn.getIdentityCorrected()).isTrue();
        assertThat(customer.getProfileDetails().getIdentityDocumentNumber()).isEqualTo("079203009988");
        assertThat(customer.getProfileDetails().getIdentityVerifiedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(customer.getIdentityDocumentHash()).isEqualTo(CustomerIdentityPolicy.hash("079203009988"));
        verify(userRepository, never()).saveAndFlush(customer);
        assertThatThrownBy(() -> service.correctIdentity(actor, checkIn.getId(), new VerifyCheckInIdentityRequest("079203009980", "Sửa lần hai", false, true)))
            .hasMessageContaining("đã sử dụng lượt sửa");
    }

    @Test
    void signsContractUsingCorrectedHandoverIdentityWhileAccountRemainsUnchanged() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        reservation.getCustomer().getProfileDetails().setIdentityDocumentNumber("079203009988");
        reservation.setIdentityDocumentNumber("079203009989");
        checkIn.setVerifiedIdentityDocument("079203009989");
        checkIn.setStatus(CheckInStatus.identity_verified);
        var evidence = entityWithId(new FileAsset());
        evidence.setStatus(FileAssetStatus.ACTIVE);
        evidence.setContentType("application/pdf");
        evidence.setEntityType("CHECK_IN");
        evidence.setEntityId(checkIn.getId());
        when(fileAssetRepository.findById(evidence.getId())).thenReturn(Optional.of(evidence));
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(checkInRepository.saveAndFlush(checkIn)).thenReturn(checkIn);
        when(assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservation.getId())).thenReturn(Optional.of(assignment));

        var signed = service.signContract(actor, checkIn.getId(), new com.storagehub.api.checkin.SignCheckInContractRequest(evidence.getId()));
        assertThat(signed.contractEvidenceCode()).isEqualTo("HD-" + reservation.getReservationCode());
        assertThat(signed.contractEvidenceReference()).isEqualTo(evidence.getId().toString());

        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.contract_signed);
        assertThat(checkIn.getVerifiedIdentityDocument()).isEqualTo("079203009989");
        assertThat(reservation.getCustomer().getProfileDetails().getIdentityDocumentNumber()).isEqualTo("079203009988");
        verify(userRepository, never()).saveAndFlush(reservation.getCustomer());
    }

    @Test
    void preventsInventoryVerificationBeforeCustomerSetupIsCompleted() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.complete(actor, checkIn.getId(), validCompletionRequest()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("phải báo đã chuẩn bị hàng xong");
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
            .hasMessageContaining("Giấy tờ không khớp");
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
            .hasMessageContaining("Giấy tờ không khớp");
        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.setup_completed);
    }

    @Test
    void rejectsLegacyReservationWithoutDeclaredIdentity() {
        reservation.setIdentityDocumentNumber(null);
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        mockCompletionChecks(false);

        assertThatThrownBy(() -> service.complete(actor, checkIn.getId(), validCompletionRequest()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("chưa có giấy tờ");
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
    void recordsArrivalOnceAndProtectsACustomerWaitingForVerificationPastDeadline() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        reservation.setHandoverTermsVersion(ReservationCheckInPolicy.ATTENDANCE_TERMS);
        reservation.setAppointmentAt(Instant.now().minusSeconds(13 * 3600));
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(storageUnitRepository.findByIdForUpdate(unit.getId())).thenReturn(Optional.of(unit));
        when(assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservation.getId()))
            .thenReturn(Optional.of(assignment));

        var first = service.recordArrival(actor, checkIn.getId()).attendance().arrivedAt();
        assertThat(first).isNotNull();
        assertThat(ReservationCheckInPolicy.completionDeadline(reservation)).isEqualTo(first.plusSeconds(72 * 3600));
        assertThat(service.recordArrival(actor, checkIn.getId()).attendance().arrivedAt()).isEqualTo(first);

        Instant afterDeadline = ReservationCheckInPolicy.attendanceDeadline(reservation.getAppointmentAt()).plusSeconds(1);
        try (var clock = org.mockito.Mockito.mockStatic(Instant.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(afterDeadline);
            service.processAttendanceDeadline(reservation.getId());
        }
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.READY_FOR_CHECKIN);
        assertThat(assignment.getStatus()).isEqualTo(UnitAssignmentStatus.ACTIVE);
        verify(notificationService, never()).createNotification(any(), any(), any(), any(), any());
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
    void requiresAcceptedAndExplainedGoodsMaterialDifference() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        mockCompletionChecks(false);
        ReservationGoodsItem declaredItem = entityWithId(new ReservationGoodsItem());
        declaredItem.setQuantity(3);
        declaredItem.setCategoryName("Đồ gia dụng");
        declaredItem.setMaterialName("Gỗ");
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId()))
            .thenReturn(List.of(declaredItem));
        CompleteCheckInRequest base = validCompletionRequest();
        CompleteCheckInRequest changed = new CompleteCheckInRequest(
            base.checklist(), base.identityDocumentNumber(), base.identityVerificationNote(),
            new CheckInMeasurements(
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("100"), BigDecimal.ONE, false
            ), base.varianceReason(), base.initialUnitCondition(), base.goodsCondition(),
            base.packageCount(), base.goodsCategory(), "Kim loại", base.evidenceReferences(),
            base.handedOverItems(), base.notes(), base.lockReference(), base.inventoryLines()
        );

        assertThatThrownBy(() -> service.complete(actor, checkIn.getId(), changed))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("chất liệu chính");
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
        reservation.setHandoverArrivedAt(Instant.now());
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        mockCompletionChecks(false);
        checkIn.setStatus(CheckInStatus.scheduled);
        reservation.setAppointmentAt(Instant.now().plusSeconds(3600));
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
        verify(notificationService).createNotification(
            any(), eq(NotificationType.CHECKIN), eq("Từ chối nhận kho"), any(), eq(reservation.getId())
        );
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

    @Test
    void recordsHandoverAndStartsPreparationWithoutCustomerConfirmation() {
        var at=Instant.parse("2026-10-30T03:00:00Z");
        var snapshot=pricingSnapshotRepository.findByReservation_Id(reservation.getId()).orElseThrow();
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);checkIn.setStatus(CheckInStatus.payment_collected);
        when(assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservation.getId())).thenReturn(Optional.of(assignment));
        // Keep the planned dates until the customer confirms receipt.
        reservation.setStartDate(LocalDate.of(2025,1,1));reservation.setEndDate(LocalDate.of(2025,2,1));
        var balance=entityWithId(new com.storagehub.domain.model.Payment());balance.setReservation(reservation);
        balance.setPurpose(com.storagehub.domain.model.PaymentType.RENTAL_BALANCE);
        balance.setStatus(com.storagehub.domain.model.PaymentStatus.PAID);balance.setAmount(snapshot.getDueAtCheckIn());
        when(paymentRepository.findTopByReservation_IdAndPurposeOrderByCreatedAtDesc(reservation.getId(),com.storagehub.domain.model.PaymentType.RENTAL_BALANCE)).thenReturn(Optional.of(balance));
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(checkInRepository.saveAndFlush(checkIn)).thenReturn(checkIn);
        UUID evidenceId = UUID.randomUUID();
        FileAsset evidence = new FileAsset();
        ReflectionTestUtils.setField(evidence, "id", evidenceId);
        evidence.setEntityType("CHECK_IN");
        evidence.setEntityId(checkIn.getId());
        when(fileAssetRepository.findAllById(List.of(evidenceId))).thenReturn(List.of(evidence));
        var preparation = org.mockito.Mockito.mock(CustomerReservationService.class);
        ReflectionTestUtils.setField(service, "customerReservations", preparation);
        org.mockito.Mockito.doAnswer(invocation -> {
            reservation.setStatus(ReservationStatus.PREPARING);
            checkIn.setStatus(CheckInStatus.setup_in_progress);
            checkIn.setSetupStartedAt(at);
            checkIn.setSetupDueAt(at.plusSeconds(86400));
            return null;
        }).when(preparation).startPreparationAfterHandover(reservation.getId(), at, staff);
        try(var time=org.mockito.Mockito.mockStatic(Instant.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(at);
            service.startSetup(actor, checkIn.getId(), new com.storagehub.api.checkin.StartCheckInSetupRequest(
                "TEST-LOCK", "TEST handover", "Kho trống và sạch", List.of(evidenceId.toString())));
        }
        assertThat(checkIn.getCheckedInAt()).isEqualTo(at);
        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.setup_in_progress);
        assertThat(checkIn.getSetupStartedAt()).isEqualTo(at);
        verify(preparation).startPreparationAfterHandover(reservation.getId(), at, staff);
        assertThat(checkIn.getRental()).isNull();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PREPARING);
        assertThat(reservation.getStartDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        verify(rentalRepository, never()).saveAndFlush(any());
    }

    @Test
    void releasesAssignedUnitAfterThreeDaysWithoutCustomerReceiptConfirmation() {
        reservation.setStatus(ReservationStatus.AWAITING_CUSTOMER_RECEIPT);
        checkIn.setStatus(CheckInStatus.handover_pending_receipt);
        unit.setStatus(StorageUnitStatus.assigned);
        assertThatThrownBy(() -> service.releaseAfterCustomerReceiptTimeout(managerActor, checkIn.getId()))
            .hasMessageContaining("Đã bỏ giải phóng");
        assertThat(reservation.getAssignedUnit()).isNotNull();
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.assigned);
        verify(storageUnitRepository, never()).saveAndFlush(any());
    }

    @Test
    void staffCannotReleaseAssignedUnitAfterCustomerReceiptTimeout() {
        assertThatThrownBy(() -> service.releaseAfterCustomerReceiptTimeout(actor, checkIn.getId()))
            .hasMessageContaining("Đã bỏ giải phóng");
        var admin = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.ADMIN),
            Set.of(SystemPermission.MANAGE_RENTALS.code()), Map.of());
        assertThatThrownBy(() -> service.releaseAfterCustomerReceiptTimeout(admin, checkIn.getId()))
            .hasMessageContaining("Đã bỏ giải phóng");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {-3600, 0, 3600})
    void recordsActualArrivalEarlyOnTimeOrLateBeforeDeadline(long differenceSeconds) {
        mockCompletionChecks(false);
        reservation.setStatus(ReservationStatus.PREPARING);
        checkIn.setInventoryArrivedAt(null);
        checkIn.setInventoryIdentityVerifiedAt(null);
        Instant appointment = Instant.parse("2026-11-23T03:00:00Z");
        Instant arrivedAt = appointment.plusSeconds(differenceSeconds);
        checkIn.setInventoryAppointmentAt(appointment);
        checkIn.getRental().setInventoryFinalDeadlineAt(appointment.plusSeconds(7200));
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservation.getId()))
            .thenReturn(Optional.of(assignment));

        try (var clock = org.mockito.Mockito.mockStatic(Instant.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(arrivedAt);
            var result = service.recordInventoryArrival(actor, checkIn.getId());
            assertThat(result.inventoryVisit().appointmentAt()).isEqualTo(appointment);
            assertThat(result.inventoryVisit().arrivedAt()).isEqualTo(arrivedAt);
            clock.when(Instant::now).thenReturn(arrivedAt.plusSeconds(60));
            assertThat(service.recordInventoryArrival(actor, checkIn.getId()).inventoryVisit().arrivedAt()).isEqualTo(arrivedAt);
        }
        verify(checkInRepository).saveAndFlush(checkIn);
    }

    @Test
    void rejectsArrivalAtTheInventoryDeadline() {
        mockCompletionChecks(false);
        reservation.setStatus(ReservationStatus.PREPARING);
        checkIn.setInventoryArrivedAt(null);
        Instant deadline = Instant.parse("2026-11-23T03:00:00Z");
        checkIn.getRental().setInventoryFinalDeadlineAt(deadline);
        try (var clock = org.mockito.Mockito.mockStatic(Instant.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(deadline);
            assertThatThrownBy(() -> service.recordInventoryArrival(actor, checkIn.getId()))
                .hasMessageContaining("hết hạn kiểm kê");
        }
        assertThat(checkIn.getInventoryArrivedAt()).isNull();
        verify(checkInRepository, never()).saveAndFlush(any());
    }

    @Test
    void verifiesRegisteredPassportAndRetainsPrivatePhotoAndVerifier() {
        mockCompletionChecks(false);
        reservation.setStatus(ReservationStatus.PREPARING);
        reservation.setIdentityDocumentNumber("P1234567");
        checkIn.setInventoryIdentityPhotoId(null);
        FileAsset photo = inventoryPhoto();
        when(fileAssetRepository.findById(photo.getId())).thenReturn(Optional.of(photo));
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservation.getId()))
            .thenReturn(Optional.of(assignment));

        var result = service.verifyInventoryIdentity(actor, checkIn.getId(), new VerifyInventoryIdentityRequest(
            "p1234567", photo.getId(), true));

        assertThat(result.inventoryVisit().photoId()).isEqualTo(photo.getId());
        assertThat(result.inventoryVisit().verifiedByName()).isEqualTo(staff.getFullName());
        assertThat(result.inventoryVisit().identityVerifiedAt()).isAfterOrEqualTo(checkIn.getInventoryArrivedAt());
        assertThat(reservation.getIdentityDocumentNumber()).isEqualTo("P1234567");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"proxy", "wrong number", "other record", "old photo", "pdf", "other uploader"})
    void rejectsInvalidInventoryVerification(String scenario) {
        mockCompletionChecks(false);
        reservation.setStatus(ReservationStatus.PREPARING);
        checkIn.setInventoryIdentityVerifiedAt(null);
        FileAsset photo = inventoryPhoto();
        String identity = reservation.getIdentityDocumentNumber();
        boolean customerPresent = !scenario.equals("proxy");
        if (scenario.equals("wrong number")) identity = "079203009999";
        if (scenario.equals("other record")) photo.setEntityId(UUID.randomUUID());
        if (scenario.equals("old photo")) ReflectionTestUtils.setField(photo, "createdAt", checkIn.getInventoryArrivedAt().minusSeconds(1));
        if (scenario.equals("pdf")) photo.setContentType("application/pdf");
        if (scenario.equals("other uploader")) photo.setUploadedBy(manager);
        if (customerPresent && !scenario.equals("wrong number")) {
            when(fileAssetRepository.findById(photo.getId())).thenReturn(Optional.of(photo));
        }

        var request = new VerifyInventoryIdentityRequest(identity, photo.getId(), customerPresent);
        assertThatThrownBy(() -> service.verifyInventoryIdentity(actor, checkIn.getId(), request))
            .isInstanceOf(ApiException.class);
        assertThat(checkIn.getInventoryIdentityVerifiedAt()).isNull();
        verify(checkInRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsInventoryCompletionUntilTheVisitIdentityIsVerified() {
        mockCompletionChecks(false);
        reservation.setStatus(ReservationStatus.PREPARING);
        checkIn.setInventoryIdentityVerifiedAt(null);

        assertThatThrownBy(() -> service.complete(actor, checkIn.getId(), validCompletionRequest()))
            .hasMessageContaining("xác minh đúng người đặt kho");
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.reserved);
        verify(rentalRepository, never()).saveAndFlush(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void opensRemovalWindowAtPublicationOnlyWhenGoodsDoNotFit(boolean exceedsCapacity) {
        mockCompletionChecks(true);
        reservation.setStatus(ReservationStatus.PREPARING);
        var declaredLine = entityWithId(new ReservationGoodsItem());
        declaredLine.setQuantity(3);
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId()))
            .thenReturn(List.of(declaredLine));
        BigDecimal weight = new BigDecimal(exceedsCapacity ? "1001" : "100");
        var line = new CompleteCheckInRequest.InventoryLine(declaredLine.getId(), 3,
            CompleteCheckInRequest.InventoryLine.Condition.DAMAGED, "Bao bì hư hỏng",
            new CompleteCheckInRequest.InventoryMeasurements(new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("100"), weight));
        var base = validCompletionRequest();
        var measurements = new CheckInMeasurements(new BigDecimal("100"), new BigDecimal("100"),
            new BigDecimal("100"), weight, new BigDecimal("3"), true);
        var request = new CompleteCheckInRequest(base.checklist(), base.identityDocumentNumber(),
            base.identityVerificationNote(), measurements, "Đã ghi nhận sai lệch", base.initialUnitCondition(),
            base.goodsCondition(), base.packageCount(), base.goodsCategory(), base.evidenceReferences(),
            base.handedOverItems(), base.notes(), base.lockReference(), List.of(line));
        Instant publishedAt = Instant.now();
        try (var clock = org.mockito.Mockito.mockStatic(Instant.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(publishedAt);
            var response = service.complete(actor, checkIn.getId(), request);
            assertThat(response.inventoryResolutionStatus())
                .isEqualTo(com.storagehub.domain.model.InventoryResolutionStatus.PENDING_CUSTOMER);
            assertThat(response.inventoryFullFit()).isEqualTo(!exceedsCapacity);
            Rental rental = checkIn.getRental();
            assertThat(rental.getInventoryRemovalNotifiedAt()).isEqualTo(exceedsCapacity ? publishedAt : null);
            assertThat(rental.getInventoryRemovalDeadlineAt())
                .isEqualTo(exceedsCapacity ? InventoryTimelinePolicy.removalDeadline(publishedAt) : null);
        }
    }

    @Test
    void rejectsHandoverAfterSevenElapsedDaysAndArrivalReview() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        reservation.setAppointmentAt(Instant.parse("2026-11-21T08:00:00Z"));
        checkIn.setStatus(CheckInStatus.payment_collected);
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        var request = new com.storagehub.api.checkin.StartCheckInSetupRequest(
            "TEST-LOCK", null, "Kho trống và sạch", List.of(UUID.randomUUID().toString()));
        var cutoff = Instant.parse("2026-11-28T08:30:00Z").plusNanos(1000);
        assertThat(ReservationCheckInPolicy.handoverExpired(reservation.getAppointmentAt(), cutoff.minusNanos(1000))).isFalse();
        try (var time = org.mockito.Mockito.mockStatic(Instant.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(cutoff);
            assertThatThrownBy(() -> service.startSetup(actor, checkIn.getId(), request))
                .hasMessageContaining("Đã hết hạn nhận kho");
        }
        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.payment_collected);
    }

    @Test
    void deadlineStartsOnArrivalAndComparisonsDoNotRestartIt() {
        reservation.setHandoverArrivedAt(Instant.now().minusSeconds(86400));
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(userRepository.findByIdForUpdate(reservation.getCustomer().getId())).thenReturn(Optional.of(reservation.getCustomer()));
        assertThat(ReservationCheckInPolicy.completionDeadline(reservation)).isEqualTo(reservation.getHandoverArrivedAt().plusSeconds(72 * 3600));
        assertThatThrownBy(() -> service.compareIdentity(actor, checkIn.getId(), "079203009989", false))
            .hasMessageContaining("xác nhận đúng người đặt kho");
        assertThat(ReservationCheckInPolicy.completionDeadline(reservation)).isEqualTo(reservation.getHandoverArrivedAt().plusSeconds(72 * 3600));
        assertThat(service.compareIdentity(actor, checkIn.getId(), "079203009989", true))
            .isEqualTo(CheckInHandoverService.IdentityComparison.CORRECTABLE);
        Instant first = reservation.getHandoverArrivedAt();
        service.compareIdentity(actor, checkIn.getId(), "079203009988", true);
        assertThat(ReservationCheckInPolicy.completionDeadline(reservation)).isEqualTo(first.plusSeconds(72 * 3600));
    }

    @Test
    void schedulerCancelsUnfinishedPaidHandoverOnlyAfterDeadline() {
        Instant start = Instant.parse("2026-11-25T03:29:00Z");
        reservation.setFirstHandoverComparisonAt(start);
        reservation.setHandoverArrivedAt(start.minusSeconds(120));
        checkIn.setStatus(CheckInStatus.payment_collected);
        when(checkInRepository.attendanceCheckInId(reservation.getId())).thenReturn(Optional.of(checkIn.getId()));
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        Instant cutoff = reservation.getHandoverArrivedAt().plusSeconds(72 * 3600);
        Instant expired = cutoff.plusNanos(1000);
        try (var clock = org.mockito.Mockito.mockStatic(Instant.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(cutoff);
            service.processAttendanceDeadline(reservation.getId());
            assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.payment_collected);
            clock.when(Instant::now).thenReturn(expired);
            service.processAttendanceDeadline(reservation.getId());
            service.processAttendanceDeadline(reservation.getId());
        }
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.cancelled);
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.maintenance);
        assertThat(reservation.getCancellationChargeAmount()).isZero();
    }

    @Test
    void lateRecordingRequiresEvidenceAndTimelyArrivalEvenAtFinalReviewInstant() {
        reservation.setHandoverTermsVersion(ReservationCheckInPolicy.ATTENDANCE_TERMS);
        reservation.setAppointmentAt(Instant.parse("2026-11-21T03:30:00Z"));
        Instant deadline = ReservationCheckInPolicy.attendanceDeadline(reservation.getAppointmentAt());
        Instant recorded = deadline.plusSeconds(1800);
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservation.getId()))
            .thenReturn(Optional.of(assignment));
        var review = new com.storagehub.api.checkin.CheckInController.ArrivalReview(deadline.minusSeconds(1), "Sổ quầy, dòng 12");
        try (var clock = org.mockito.Mockito.mockStatic(Instant.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(recorded);
            service.processAttendanceDeadline(reservation.getId());
            assertThatThrownBy(() -> service.recordArrival(actor, checkIn.getId())).hasMessageContaining("căn cứ");
            service.recordArrival(actor, checkIn.getId(), review);
        }
        assertThat(reservation.getHandoverArrivedAt()).isEqualTo(review.actualArrivedAt());
        assertThat(reservation.getFirstHandoverComparisonAt()).isNull();
    }

    @Test
    void disputedArrivalSurvivesCutoffUntilManagerConfirms() {
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        reservation.setHandoverTermsVersion(ReservationCheckInPolicy.ATTENDANCE_TERMS);
        reservation.setAppointmentAt(Instant.now().minusSeconds(12 * 86400));
        Instant actual = ReservationCheckInPolicy.attendanceDeadline(reservation.getAppointmentAt()).minusSeconds(60);
        reservation.setDisputedArrivalAt(actual);
        reservation.setArrivalReviewOpenedAt(actual.plusSeconds(120));
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(userRepository.findById(managerActor.userId())).thenReturn(Optional.of(manager));
        when(assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservation.getId())).thenReturn(Optional.of(assignment));
        service.processAttendanceDeadline(reservation.getId());
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.READY_FOR_CHECKIN);
        service.resolveArrival(managerActor, checkIn.getId(), true, "Đã kiểm tra sổ quầy");
        assertThat(reservation.getHandoverArrivedAt()).isEqualTo(actual);
        assertThat(reservation.getFirstHandoverComparisonAt()).isNull();
    }

    @Test
    void remindsEveryTwoHoursInFinalDayWithoutRepeatingAnAlreadySentReminder() {
        reservation.setHandoverTermsVersion(ReservationCheckInPolicy.ATTENDANCE_TERMS);
        reservation.setAppointmentAt(Instant.parse("2026-11-21T03:30:00Z"));
        reservation.setAttendanceReminderHours(12); // Already reminded 12 hours after the appointment.
        Instant deadline = ReservationCheckInPolicy.attendanceDeadline(reservation.getAppointmentAt());
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        for (int hours = 24; hours >= 2; hours -= 2) {
            Instant due = deadline.minusSeconds(hours * 3600L);
            Instant after = due.plusSeconds(3599);
            try (var clock = org.mockito.Mockito.mockStatic(Instant.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
                clock.when(Instant::now).thenReturn(due);
                service.processAttendanceDeadline(reservation.getId());
                clock.when(Instant::now).thenReturn(after);
                service.processAttendanceDeadline(reservation.getId());
            }
            verify(notificationService).createNotification(eq(reservation.getCustomer().getId()), any(),
                eq("Nhắc hạn nhận kho (" + hours + " giờ)"), any(), eq(reservation.getId()));
        }
        verifyNoMoreInteractions(notificationService);
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
        rental.setInventoryPending(true);
        rental.setSetupEndsAt(Instant.now().minusSeconds(1));
        rental.setStartDate(reservation.getStartDate());
        checkIn.setRental(rental);
        org.mockito.Mockito.lenient().when(rentalRepository.findByReservationIdForUpdate(reservation.getId())).thenReturn(Optional.of(rental));
        checkIn.setInventoryAppointmentAt(Instant.now().minusSeconds(120));
        checkIn.setInventoryArrivedAt(Instant.now().minusSeconds(100));
        checkIn.setInventoryIdentityVerifiedAt(Instant.now().minusSeconds(60));
        checkIn.setInventoryIdentityVerifiedBy(staff);
        FileAsset photo = inventoryPhoto();
        checkIn.setInventoryIdentityPhotoId(photo.getId());
        org.mockito.Mockito.lenient().when(fileAssetRepository.findById(photo.getId())).thenReturn(Optional.of(photo));
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
            org.mockito.Mockito.lenient().when(assignmentRepository.saveAndFlush(assignment)).thenReturn(assignment);
        }
    }

    @Test
    void archivesCompletedCheckInWithAuditLog() {
        checkIn.setStatus(CheckInStatus.completed);
        reservation.setStatus(ReservationStatus.COMPLETED);
        when(checkInRepository.findById(checkIn.getId())).thenReturn(Optional.of(checkIn));

        service.archiveCompleted(actor, checkIn.getId());

        assertThat(reservation.getArchivedAt()).isNotNull();
        verify(reservationRepository).saveAndFlush(reservation);
        verify(auditLogService).recordMutation(
            isNull(), eq("CHECKIN_ORDER_DELETED"), eq("Reservation"), eq(reservation.getId()),
            eq(reservation.getFacility().getId()), any(), any()
        );
    }

    @Test
    void rejectsArchiveWhenCheckInNotCompleted() {
        checkIn.setStatus(CheckInStatus.scheduled);
        reservation.setStatus(ReservationStatus.READY_FOR_CHECKIN);
        when(checkInRepository.findById(checkIn.getId())).thenReturn(Optional.of(checkIn));

        assertThatThrownBy(() -> service.archiveCompleted(actor, checkIn.getId()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Chỉ có thể xóa/lưu trữ đơn sau khi bàn giao thành công");
    }

    @Test
    void derivesCatalogIdsFromActualGoodsWhenDeserializingInventoryLine() throws Exception {
        UUID categoryId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        String json = "{\"goodsItemId\":\"" + UUID.randomUUID()
            + "\",\"actualQuantity\":1,\"condition\":\"INTACT\",\"actualGoods\":{\"catalogCategoryId\":\""
            + categoryId + "\",\"catalogMaterialId\":\"" + materialId + "\"}}";
        var line = new ObjectMapper().readValue(json, CompleteCheckInRequest.InventoryLine.class);
        assertThat(line.actualCategoryId()).isEqualTo(categoryId);
        assertThat(line.actualMaterialId()).isEqualTo(materialId);
    }

    @Test
    void requiresPublishedInventoryBeforeSuggestingAlternativeUnits() {
        when(checkInRepository.findById(checkIn.getId())).thenReturn(Optional.of(checkIn));
        assertThatThrownBy(() -> service.inventoryAlternatives(actor, checkIn.getId()))
            .isInstanceOf(ApiException.class);
        verify(storageUnitRepository, never()).findByFacility_Id(any());
    }

    @Test
    void checksFacilityAccessBeforeReadingInventoryAlternatives() {
        when(checkInRepository.findById(checkIn.getId())).thenReturn(Optional.of(checkIn));
        org.mockito.Mockito.doThrow(com.storagehub.common.api.ApiExceptions.forbidden("Outside facility scope"))
            .when(facilityScopeService).assertCanRead(actor, reservation.getFacility().getId());
        assertThatThrownBy(() -> service.inventoryAlternatives(actor, checkIn.getId()))
            .hasMessageContaining("Outside facility scope");
        verify(storageUnitRepository, never()).findByFacility_Id(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void suggestsOnlyUnitsWithACompletePackingPlan(boolean fits) throws Exception {
        checkIn.setStatus(CheckInStatus.setup_completed);
        checkIn.setInventoryActualJson("{}");
        var original = entityWithId(new ReservationGoodsItem());
        original.setQuantity(3);
        var base = validCompletionRequest();
        var line = new CompleteCheckInRequest.InventoryLine(original.getId(), 3,
            CompleteCheckInRequest.InventoryLine.Condition.INTACT, null,
            new CompleteCheckInRequest.InventoryMeasurements(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN));
        var report = new CompleteCheckInRequest(base.checklist(), base.identityDocumentNumber(), null,
            base.actualMeasurements(), null, base.initialUnitCondition(), base.goodsCondition(), 3,
            base.goodsCategory(), base.goodsMaterial(), base.evidenceReferences(), base.handedOverItems(),
            null, base.lockReference(), List.of(line));
        checkIn.setChecklistJson(new ObjectMapper().writeValueAsString(report));
        when(checkInRepository.findById(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(goodsItemRepository.findAllByReservation_IdOrderByCreatedAtAsc(reservation.getId())).thenReturn(List.of(original));
        var candidate = entityWithId(new StorageUnit());
        candidate.setCode("ALTERNATIVE-01"); candidate.setUnitType(unit.getUnitType());
        candidate.getUnitType().setStatus(com.storagehub.domain.model.UnitTypeStatus.active);
        candidate.setStatus(StorageUnitStatus.available);
        when(storageUnitRepository.findByFacility_Id(reservation.getFacility().getId())).thenReturn(List.of(candidate));
        var catalog = org.mockito.Mockito.mock(GoodsCatalogService.class);
        var packing = org.mockito.Mockito.mock(PalletInventoryService.class);
        ReflectionTestUtils.setField(service, "goodsCatalogService", catalog);
        ReflectionTestUtils.setField(service, "palletInventory", packing);
        var plan = fits ? org.mockito.Mockito.mock(com.storagehub.api.reservation.PalletPlan.class) : null;
        when(packing.evaluateActualFit(eq(candidate), any())).thenReturn(new PalletInventoryService.Fit(plan, 1, candidate, List.of(), false));
        var suggestions = service.inventoryAlternatives(actor, checkIn.getId());
        assertThat(suggestions).hasSize(fits ? 1 : 0);
        if (fits) assertThat(suggestions.getFirst().storageUnitId()).isEqualTo(candidate.getId());
    }


    private void prepareDraft() {
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        checkIn.setStatus(CheckInStatus.setup_completed);
        checkIn.setInventoryArrivedAt(Instant.now());
        checkIn.setInventoryIdentityVerifiedAt(Instant.now());
        Rental rental = entityWithId(new Rental());
        rental.setInventoryFinalDeadlineAt(Instant.now().plusSeconds(86400));
        when(rentalRepository.findByReservationIdForUpdate(reservation.getId())).thenReturn(Optional.of(rental));
    }

    @Test
    void savesAndReadsDraftWithoutPublishingOrChangingOriginalHandover() {
        prepareDraft();
        var request = validCompletionRequest();
        UUID fileId = UUID.fromString(request.evidenceReferences().getFirst());
        FileAsset evidence = entityWithId(new FileAsset());
        ReflectionTestUtils.setField(evidence, "id", fileId);
        evidence.setEntityType("CHECK_IN"); evidence.setEntityId(checkIn.getId());
        when(fileAssetRepository.findAllById(List.of(fileId))).thenReturn(List.of(evidence));
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        String original = checkIn.getChecklistJson();
        var saved = service.saveInventoryDraft(actor, checkIn.getId(), new com.storagehub.api.checkin.SaveInventoryDraftRequest(request, 0));
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(saved.savedAt()).isNotNull();
        assertThat(checkIn.getChecklistJson()).isEqualTo(original);
        assertThat(checkIn.getInventoryResolutionStatus()).isEqualTo(com.storagehub.domain.model.InventoryResolutionStatus.NOT_STARTED);
        verify(notificationService, never()).createNotification(any(), any(), any(), any(), any());
        when(checkInRepository.findById(checkIn.getId())).thenReturn(Optional.of(checkIn));
        assertThat(service.getInventoryDraft(actor, checkIn.getId()).request()).isEqualTo(request);
    }

    @Test
    void rejectsDraftOverwriteWithStaleRevision() {
        prepareDraft(); checkIn.setInventoryDraftRevision(2);
        assertThatThrownBy(() -> service.saveInventoryDraft(actor, checkIn.getId(), new com.storagehub.api.checkin.SaveInventoryDraftRequest(validCompletionRequest(), 1)))
            .isInstanceOf(ApiException.class).hasMessageContaining("người khác");
        verify(checkInRepository, never()).saveAndFlush(any());
    }

    @Test
    void expired72HoursPreventsSavingDraftBeforeAnyEvidenceOrRecordIsChanged() {
        prepareDraft();
        var rental = rentalRepository.findByReservationIdForUpdate(reservation.getId()).orElseThrow();
        rental.setInspectionClockStartedAt(Instant.now().minus(java.time.Duration.ofHours(73)));
        rental.setInspectionCompletionDeadlineAt(Instant.now().minusSeconds(3600));
        assertThatThrownBy(() -> service.saveInventoryDraft(actor, checkIn.getId(),
            new com.storagehub.api.checkin.SaveInventoryDraftRequest(validCompletionRequest(), 0)))
            .isInstanceOf(ApiException.class).hasMessageContaining("72 giờ");
        assertThat(checkIn.getInventoryDraftJson()).isNull();
        verify(checkInRepository, never()).saveAndFlush(any());
        verify(notificationService, never()).createNotification(any(), any(), any(), any(), any());
    }

    @Test
    void recoveryPreventsSavingDraftWhileTheOriginalCheckInStillLooksOpen() {
        prepareDraft();
        var rental = rentalRepository.findByReservationIdForUpdate(reservation.getId()).orElseThrow();
        rental.setInventoryEscalatedAt(Instant.now());
        assertThatThrownBy(() -> service.saveInventoryDraft(actor, checkIn.getId(),
            new com.storagehub.api.checkin.SaveInventoryDraftRequest(validCompletionRequest(), 0)))
            .isInstanceOf(ApiException.class).hasMessageContaining("thu hồi");
        verify(checkInRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsDraftSaveAfterResultWasSentToCustomer() {
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        checkIn.setInventoryResolutionStatus(com.storagehub.domain.model.InventoryResolutionStatus.PENDING_CUSTOMER);
        assertThatThrownBy(() -> service.saveInventoryDraft(actor, checkIn.getId(), new com.storagehub.api.checkin.SaveInventoryDraftRequest(validCompletionRequest(), 0)))
            .isInstanceOf(ApiException.class);
        verify(checkInRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsPublishingAnUnsavedOrReplacedDraft() {
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        assertThatThrownBy(() -> service.publishInventoryDraft(actor, checkIn.getId(), 0)).isInstanceOf(ApiException.class);
        verify(notificationService, never()).createNotification(any(), any(), any(), any(), any());
    }

    @Test
    void deniesDraftReadOutsideTheFacility() {
        when(checkInRepository.findById(checkIn.getId())).thenReturn(Optional.of(checkIn));
        org.mockito.Mockito.doThrow(com.storagehub.common.api.ApiExceptions.forbidden("Outside facility scope"))
            .when(facilityScopeService).assertCanRead(actor, reservation.getFacility().getId());
        assertThatThrownBy(() -> service.getInventoryDraft(actor, checkIn.getId())).isInstanceOf(ApiException.class);
    }


    @Test
    void onsiteResolutionRequiresPhysicalCustomerAcknowledgement() {
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        assertThatThrownBy(() -> service.resolveInventoryAtSite(actor, checkIn.getId(), new com.storagehub.api.checkin.ResolveInventoryAtSiteRequest(
            com.storagehub.api.reservation.InventoryResolutionRequest.Decision.ACCEPT_FULL, false, null, null)))
            .isInstanceOf(ApiException.class).hasMessageContaining("chính chủ");
        verify(checkInRepository, never()).saveAndFlush(any());
    }

    @Test
    void onsiteResolutionCannotAcceptAllGoodsWhenTheyDoNotFit() {
        prepareDraft();
        checkIn.setInventoryResolutionStatus(com.storagehub.domain.model.InventoryResolutionStatus.PENDING_STAFF_RESOLUTION);
        checkIn.setInventoryFullFit(false);
        assertThatThrownBy(() -> service.resolveInventoryAtSite(actor, checkIn.getId(), new com.storagehub.api.checkin.ResolveInventoryAtSiteRequest(
            com.storagehub.api.reservation.InventoryResolutionRequest.Decision.ACCEPT_FULL, true, null, null)))
            .isInstanceOf(ApiException.class).hasMessageContaining("Không có phương án xếp toàn bộ");
        verify(checkInRepository, never()).saveAndFlush(any());
    }

    @Test
    void signatureRefusalApprovalStartsDeadlineAtPublicationAndIsOneTime() throws Exception {
        var rental = prepareSignatureRefusalReview();
        var type = new com.storagehub.domain.model.UnitType();
        type.setCode("S");
        type.setLengthM(BigDecimal.ONE);
        type.setWidthM(BigDecimal.ONE);
        type.setHeightM(BigDecimal.ONE);
        unit.setUnitType(type);
        rental.setStorageUnit(unit);
        var confirmation = checkIn.getInventoryConfirmation();
        var report = new ObjectMapper().readValue(confirmation.getInventoryRefusalReport(), CheckInHandoverService.RefusalReport.class);
        var evidence = new FileAsset();
        evidence.setEntityType("CHECK_IN");
        evidence.setEntityId(checkIn.getId());
        evidence.setStatus(FileAssetStatus.ACTIVE);
        when(fileAssetRepository.findAllById(List.of(report.evidenceId()))).thenReturn(List.of(evidence));
        var settlement = mock(ReturnSettlementService.class);
        var response = mock(com.storagehub.api.returns.ReturnCaseResponse.class);
        when(response.returnType()).thenReturn(com.storagehub.domain.model.ReturnType.INVENTORY_MISMATCH);
        when(response.id()).thenReturn(UUID.randomUUID());
        when(settlement.createInventoryReturn(eq(rental), any(), eq(manager))).thenReturn(response);
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("settlement", settlement);
        ReflectionTestUtils.setField(service, "inventoryReturnServices", beans.getBeanProvider(ReturnSettlementService.class));
        ReflectionTestUtils.setField(service, "inventoryDecisions", mock(InventoryDecisionService.class));
        assertThat(rental.getInventoryRemovalDeadlineAt()).isNull();

        service.reviewInventoryRefusal(managerActor, checkIn.getId(), confirmation.getInventoryRefusalSubmittedAt(), true, "Đủ căn cứ");

        assertThat(rental.getInventoryRemovalPublishedAt()).isAfter(confirmation.getInventoryRefusalSubmittedAt());
        assertThat(rental.getInventoryRemovalDeadlineAt()).isEqualTo(InventoryTimelinePolicy.removalDeadline(rental.getInventoryRemovalPublishedAt(), 3));
        assertThat(checkIn.getInventoryResolutionStatus()).isEqualTo(com.storagehub.domain.model.InventoryResolutionStatus.RETURN_REQUESTED);
        assertThatThrownBy(() -> service.reviewInventoryRefusal(managerActor, checkIn.getId(),
            confirmation.getInventoryRefusalSubmittedAt(), true, "Gửi lại")).hasMessageContaining("đã được xử lý");
        verify(settlement).createInventoryReturn(eq(rental), any(), eq(manager));
    }

    @Test
    void signatureRefusalReturnedForCorrectionDoesNotOpenRemovalWindow() throws Exception {
        var rental = prepareSignatureRefusalReview();
        service.reviewInventoryRefusal(managerActor, checkIn.getId(),
            checkIn.getInventoryConfirmation().getInventoryRefusalSubmittedAt(), false, "Thiếu căn cứ");
        assertThat(rental.getInventoryRemovalDeadlineAt()).isNull();
        assertThat(checkIn.hasPendingInventoryRefusal()).isFalse();
        assertThat(checkIn.getInventoryResolutionStatus()).isEqualTo(com.storagehub.domain.model.InventoryResolutionStatus.PENDING_STAFF_RESOLUTION);
    }

    private Rental prepareSignatureRefusalReview() throws Exception {
        prepareDraft();
        Rental rental = rentalRepository.findByReservationIdForUpdate(reservation.getId()).orElseThrow();
        rental.setStatus(RentalStatus.setup);
        rental.setInventoryPending(true);
        checkIn.setInventoryFullFit(false);
        checkIn.setInventoryResolutionStatus(com.storagehub.domain.model.InventoryResolutionStatus.PENDING_STAFF_RESOLUTION);
        var confirmation = new com.storagehub.domain.model.CheckInInventoryConfirmation();
        confirmation.setInventoryRefusalSubmittedAt(Instant.now().minusSeconds(3600));
        confirmation.setInventoryRefusalReport(new ObjectMapper().writeValueAsString(new CheckInHandoverService.RefusalReport(
            staff.getId(), "Staff", new CheckInHandoverService.RefusalWitness(UUID.randomUUID(), "Witness"),
            UUID.randomUUID(), "Không vừa gian", "Đã trao đổi trực tiếp", null, 0)));
        checkIn.setInventoryConfirmation(confirmation);
        when(userRepository.findById(managerActor.userId())).thenReturn(Optional.of(manager));
        when(assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservation.getId())).thenReturn(Optional.of(assignment));
        return rental;
    }

    @Test
    void managerCannotRecordAnOnsiteInventoryDecision() {
        assertThatThrownBy(() -> service.resolveInventoryAtSite(managerActor, checkIn.getId(), new com.storagehub.api.checkin.ResolveInventoryAtSiteRequest(
            com.storagehub.api.reservation.InventoryResolutionRequest.Decision.ACCEPT_FULL, true, null, null)))
            .isInstanceOf(ApiException.class);
        verify(checkInRepository, never()).findByIdForUpdate(any());
    }


    /** A signed paper record photographed at the facility is the consent evidence. */
    private String onsiteConsentEvidence() {
        UUID evidenceId = UUID.fromString("0d29e3dd-67a8-4c8c-bccd-d54e374f50b0");
        FileAsset evidence = entityWithId(new FileAsset());
        ReflectionTestUtils.setField(evidence, "id", evidenceId);
        evidence.setEntityType("CHECK_IN");
        evidence.setEntityId(checkIn.getId());
        evidence.setStatus(FileAssetStatus.ACTIVE);
        when(fileAssetRepository.findAllById(List.of(evidenceId))).thenReturn(List.of(evidence));
        return evidenceId.toString();
    }

    @Test
    void onsiteForcedReturnCreatesLinkedCaseEvenBeforeCustomerSchedules() {
        prepareDraft();
        checkIn.setInventoryResolutionStatus(com.storagehub.domain.model.InventoryResolutionStatus.PENDING_STAFF_RESOLUTION);
        checkIn.setInventoryFullFit(false);
        var rental = entityWithId(new Rental());
        unit.getUnitType().setCode("S");
        rental.setStorageUnit(unit);
        rental.setInventoryFinalDeadlineAt(Instant.now().plusSeconds(86_400));
        when(rentalRepository.findByReservationIdForUpdate(reservation.getId())).thenReturn(Optional.of(rental));
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservation.getId())).thenReturn(Optional.of(assignment));
        var settlement = mock(ReturnSettlementService.class);
        var response = mock(com.storagehub.api.returns.ReturnCaseResponse.class);
        when(response.returnType()).thenReturn(com.storagehub.domain.model.ReturnType.INVENTORY_MISMATCH);
        when(settlement.createInventoryReturnAtSite(eq(actor), eq(rental.getId()), any())).thenReturn(response);
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("settlement", settlement);
        ReflectionTestUtils.setField(service, "inventoryReturnServices", beans.getBeanProvider(ReturnSettlementService.class));
        var decisions = mock(InventoryDecisionService.class);
        ReflectionTestUtils.setField(service, "inventoryDecisions", decisions);
        String consent = onsiteConsentEvidence();
        var decision = com.storagehub.api.reservation.InventoryResolutionRequest.Decision.CANCEL_AND_RETURN;

        service.resolveInventoryAtSite(actor, checkIn.getId(), new com.storagehub.api.checkin.ResolveInventoryAtSiteRequest(
            decision, true, "Hàng không vừa gian", null, List.of(consent)));

        var sent = org.mockito.ArgumentCaptor.forClass(com.storagehub.api.returns.CustomerCreateReturnRequest.class);
        verify(settlement).createInventoryReturnAtSite(eq(actor), eq(rental.getId()), sent.capture());
        assertThat(sent.getValue().scheduledDate()).isEqualTo(InventoryTimelinePolicy.removalEffectiveAt(rental.getInventoryRemovalPublishedAt())
            .atZone(InventoryTimelinePolicy.ZONE).toLocalDate());
        assertThat(checkIn.getInventoryResolutionStatus()).isEqualTo(com.storagehub.domain.model.InventoryResolutionStatus.RETURN_REQUESTED);
        verify(decisions).record(checkIn, decision, staff, true, List.of(UUID.fromString(consent)));
    }

    @Test
    void onsiteFullAcceptanceCompletesIntakeWithoutWaitingForCustomerAccount() {
        prepareDraft();
        checkIn.setInventoryResolutionStatus(com.storagehub.domain.model.InventoryResolutionStatus.PENDING_STAFF_RESOLUTION);
        checkIn.setInventoryFullFit(true);
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(staff));
        when(assignmentRepository.findFirstByReservation_IdOrderByAssignedAtDesc(reservation.getId())).thenReturn(Optional.of(assignment));
        String consent = onsiteConsentEvidence();
        service.resolveInventoryAtSite(actor, checkIn.getId(), new com.storagehub.api.checkin.ResolveInventoryAtSiteRequest(
            com.storagehub.api.reservation.InventoryResolutionRequest.Decision.ACCEPT_FULL, true, "Đã trao đổi tại chỗ", null,
            List.of(consent)));
        assertThat(checkIn.getStatus()).isEqualTo(CheckInStatus.completed);
        assertThat(checkIn.getInventoryResolutionStatus()).isEqualTo(com.storagehub.domain.model.InventoryResolutionStatus.ACCEPTED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.occupied);
        assertThat(checkIn.getPerformedBy()).isEqualTo(staff);
    }

    @Test
    void retiredPartialIntakeCannotBeSubmittedByStaffOrCustomer() {
        String payload = "{\"decision\":\"ACCEPT_PARTIAL\"}";
        for (Class<?> request : List.of(com.storagehub.api.checkin.ResolveInventoryAtSiteRequest.class,
            com.storagehub.api.reservation.InventoryResolutionRequest.class)) {
            assertThatThrownBy(() -> new ObjectMapper().readValue(payload, request))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
        }
    }

    /**
    /**
     * A customer who asked to return during setup leaves the rental in return_requested.
     * The inventory inspection the return case depends on must remain recordable there.
     */
    @Test
    void returnsDuringSetupAreRecognisedAsAwaitingInspection() {
        var rental = entityWithId(new Rental());
        rental.setStatus(RentalStatus.return_requested);
        var returnCase = new com.storagehub.domain.model.ReturnCase();
        returnCase.setStatus(com.storagehub.domain.model.ReturnCaseStatus.requested);
        when(returnCaseRepository.findByRental_Id(rental.getId())).thenReturn(java.util.List.of(returnCase));

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "setupReturnAwaitingInspection", rental))
            .isEqualTo(Boolean.TRUE);
    }

    /** A return_requested rental with no open return case is still not ready for inspection. */
    @Test
    void returnRequestedWithoutAnOpenCaseIsNotAwaitingInspection() {
        var rental = entityWithId(new Rental());
        rental.setStatus(RentalStatus.return_requested);
        when(returnCaseRepository.findByRental_Id(rental.getId())).thenReturn(java.util.List.of());

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "setupReturnAwaitingInspection", rental))
            .isEqualTo(Boolean.FALSE);
    }

    /** A rental still in setup with no return case is never treated as awaiting inspection. */
    @Test
    void setupRentalIsNotAwaitingInspection() {
        var rental = entityWithId(new Rental());
        rental.setStatus(RentalStatus.setup);

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "setupReturnAwaitingInspection", rental))
            .isEqualTo(Boolean.FALSE);
    }

    @Test
    void onsiteAcceptanceIsBlockedWithoutConsentEvidence() {
        // A verified identity photo only proves the customer was present. Without the signed
        // record there is no proof of agreement, so the acceptance must not be recorded.
        prepareDraft();
        checkIn.setInventoryResolutionStatus(com.storagehub.domain.model.InventoryResolutionStatus.PENDING_STAFF_RESOLUTION);
        checkIn.setInventoryFullFit(true);
        assertThatThrownBy(() -> service.resolveInventoryAtSite(actor, checkIn.getId(),
            new com.storagehub.api.checkin.ResolveInventoryAtSiteRequest(
                com.storagehub.api.reservation.InventoryResolutionRequest.Decision.ACCEPT_FULL, true, "Không kèm bằng chứng", null)))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("biên bản giấy");
        assertThat(checkIn.getInventoryResolutionStatus())
            .isEqualTo(com.storagehub.domain.model.InventoryResolutionStatus.PENDING_STAFF_RESOLUTION);
        assertThat(checkIn.getStatus()).isNotEqualTo(CheckInStatus.completed);
    }

    @Test
    void onsiteAcceptanceRejectsConsentEvidenceFromAnotherCheckIn() {
        prepareDraft();
        checkIn.setInventoryResolutionStatus(com.storagehub.domain.model.InventoryResolutionStatus.PENDING_STAFF_RESOLUTION);
        checkIn.setInventoryFullFit(true);
        UUID foreignId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        FileAsset foreign = entityWithId(new FileAsset());
        ReflectionTestUtils.setField(foreign, "id", foreignId);
        foreign.setEntityType("CHECK_IN");
        foreign.setEntityId(UUID.randomUUID());
        foreign.setStatus(FileAssetStatus.ACTIVE);
        when(fileAssetRepository.findAllById(List.of(foreignId))).thenReturn(List.of(foreign));
        assertThatThrownBy(() -> service.resolveInventoryAtSite(actor, checkIn.getId(),
            new com.storagehub.api.checkin.ResolveInventoryAtSiteRequest(
                com.storagehub.api.reservation.InventoryResolutionRequest.Decision.ACCEPT_FULL, true, null, null,
                List.of(foreignId.toString()))))
            .isInstanceOf(ApiException.class);
        assertThat(checkIn.getStatus()).isNotEqualTo(CheckInStatus.completed);
    }

    private FileAsset inventoryPhoto() {
        FileAsset photo = entityWithId(new FileAsset());
        photo.setEntityType(FileStorageService.INVENTORY_IDENTITY_ENTITY);
        photo.setEntityId(checkIn.getId());
        photo.setUploadedBy(staff);
        photo.setContentType("image/png");
        ReflectionTestUtils.setField(photo, "createdAt", checkIn.getInventoryArrivedAt().plusSeconds(1));
        return photo;
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

    @Test
    void thirdMismatchBlocksFurtherComparisonAndVerification() {
        reservation.setHandoverArrivedAt(Instant.now());
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(userRepository.findByIdForUpdate(reservation.getCustomer().getId())).thenReturn(Optional.of(reservation.getCustomer()));
        for (int n = 0; n < 3; n++) service.compareIdentity(actor, checkIn.getId(), "079203009989", true);
        assertThat(checkIn.getIdentityFailedAttempts()).isEqualTo(3);
        assertThatThrownBy(() -> service.compareIdentity(actor, checkIn.getId(), "079203009988", true)).hasMessageContaining("3 lần");
        assertThatThrownBy(() -> service.verifyIdentity(actor, checkIn.getId(), new VerifyCheckInIdentityRequest("079203009988", null, false, true))).hasMessageContaining("3 lần");
    }

    @Test
    void correctionRequiresCheckAndLocksAfterTwoInvalidChecks() {
        checkIn.setIdentityVerifiedAt(Instant.now());
        checkIn.setVerifiedIdentityDocument("079203009988");
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(userRepository.findByIdForUpdate(reservation.getCustomer().getId())).thenReturn(Optional.of(reservation.getCustomer()));
        var valid = new VerifyCheckInIdentityRequest("079203009989", "Giấy tờ gốc", false, true);
        assertThatThrownBy(() -> service.correctIdentity(actor, checkIn.getId(), valid)).hasMessageContaining("kiểm tra CCCD hợp lệ");
        assertThat(service.checkIdentityCorrection(actor, checkIn.getId(), valid).valid()).isTrue();
        assertThat(checkIn.getIdentityCorrectionFailedAttempts()).isZero();
        var invalid = new VerifyCheckInIdentityRequest("abc", null);
        assertThat(service.checkIdentityCorrection(actor, checkIn.getId(), invalid).failedAttempts()).isEqualTo(1);
        assertThat(checkIn.getApprovedIdentityCorrection()).isNull();
        assertThat(service.checkIdentityCorrection(actor, checkIn.getId(), invalid).failedAttempts()).isEqualTo(2);
        assertThatThrownBy(() -> service.checkIdentityCorrection(actor, checkIn.getId(), valid)).hasMessageContaining("2 lần");
        assertThatThrownBy(() -> service.correctIdentity(actor, checkIn.getId(), valid)).hasMessageContaining("2 lần");
    }

    @Test
    void malformedComparisonConsumesAttemptButMatchDoesNot() {
        reservation.setHandoverArrivedAt(Instant.now());
        when(checkInRepository.findByIdForUpdate(checkIn.getId())).thenReturn(Optional.of(checkIn));
        when(reservationRepository.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(userRepository.findByIdForUpdate(reservation.getCustomer().getId())).thenReturn(Optional.of(reservation.getCustomer()));
        assertThat(service.compareIdentity(actor, checkIn.getId(), "invalid", true)).isEqualTo(CheckInHandoverService.IdentityComparison.REJECT);
        assertThat(checkIn.getIdentityFailedAttempts()).isEqualTo(1);
        assertThat(service.compareIdentity(actor, checkIn.getId(), "079203009988", true)).isEqualTo(CheckInHandoverService.IdentityComparison.MATCH);
        assertThat(checkIn.getIdentityFailedAttempts()).isEqualTo(1);
    }
}
