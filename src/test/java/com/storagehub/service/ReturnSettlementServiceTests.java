package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.returns.CustomerConfirmSettlementRequest;
import com.storagehub.api.returns.CustomerCreateReturnRequest;
import com.storagehub.api.returns.CustomerPaySettlementRequest;
import com.storagehub.api.returns.ManagerReviewDisputeRequest;
import com.storagehub.api.returns.ReturnCaseResponse;
import com.storagehub.api.returns.ReturnInspectionRequest;
import com.storagehub.api.returns.StaffCompleteRefundRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.CustomerDecision;
import com.storagehub.domain.model.DamageClassification;
import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.InventoryMatch;
import com.storagehub.domain.model.MaintenanceTask;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.ReturnCase;
import com.storagehub.domain.model.ReturnCaseStatus;
import com.storagehub.domain.model.ReturnType;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.ReturnCaseRepository;
import com.storagehub.domain.repo.MaintenanceTaskRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.time.BusinessTime;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
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
class ReturnSettlementServiceTests {

    @Mock ReturnCaseRepository returnCaseRepository;
    @Mock MaintenanceTaskRepository maintenanceTaskRepository;
    @Mock com.storagehub.domain.repo.ReservationPricingSnapshotRepository pricingSnapshotRepository;
    @Mock com.storagehub.domain.repo.UserFacilityScopeRepository userFacilityScopeRepository;
    @Mock RentalRepository rentalRepository;
    @Mock StorageUnitRepository storageUnitRepository;
    @Mock UserRepository userRepository;
    @Mock AdminAuthorizationService authorizationService;
    @Mock FacilityScopeService facilityScopeService;
    @Mock NotificationService notificationService;
    @Mock AuditLogService auditLogService;
    @Mock com.storagehub.service.ledger.ReturnFinancialReconciliation returnFinance;
    @Mock jakarta.persistence.EntityManager settlementEntityManager;

    ObjectMapper objectMapper = new ObjectMapper();
    ReturnSettlementService service;

    User customer;
    User staff;
    User manager;
    Facility facility;
    StorageUnit unit;
    Rental rental;
    ActorPrincipal customerActor;
    ActorPrincipal staffActor;
    ActorPrincipal managerActor;

    @BeforeEach
    void setUp() {
        service = new ReturnSettlementService(
            returnCaseRepository,
            rentalRepository,
            storageUnitRepository,
            userRepository,
            authorizationService,
            facilityScopeService,
            notificationService,
            auditLogService,
            objectMapper
        );

        customer = new User();
        ReflectionTestUtils.setField(customer, "id", UUID.randomUUID());
        customer.setFullName("Nguyen Van Khach");
        customer.setEmail("customer@test.com");
        customer.setPhone("0901112222");

        staff = new User();
        ReflectionTestUtils.setField(staff, "id", UUID.randomUUID());
        staff.setFullName("Tran Van Staff");

        manager = new User();
        ReflectionTestUtils.setField(manager, "id", UUID.randomUUID());
        manager.setFullName("Le Van Manager");

        facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", UUID.randomUUID());
        facility.setName("StorageHub Thu Duc");

        unit = new StorageUnit();
        ReflectionTestUtils.setField(unit, "id", UUID.randomUUID());
        unit.setCode("TD-U101");
        unit.setFacility(facility);
        unit.setStatus(StorageUnitStatus.occupied);

        rental = new Rental();
        ReflectionTestUtils.setField(rental, "id", UUID.randomUUID());
        rental.setCustomer(customer);
        rental.setFacility(facility);
        rental.setStorageUnit(unit);
        rental.setMonthlyPrice(new BigDecimal("2000000.00"));
        rental.setStatus(RentalStatus.active);
        rental.setStartDate(LocalDate.now().minusMonths(3));
        rental.setContractEndDate(LocalDate.now().plusMonths(3));
        var reservation = new com.storagehub.domain.model.Reservation();
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        reservation.setReservationCode("RSV-SETUP-TEST");
        var quote = new com.storagehub.domain.model.ReservationQuote();
        quote.setSetupEarlyExitPolicyVersion(SetupEarlyExitPolicy.VERSION);
        quote.setSetupEarlyExitFee(new BigDecimal("1200000"));
        reservation.setSourceQuote(quote);
        rental.setReservation(reservation);

        customerActor = new ActorPrincipal(
            customer.getId(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Collections.emptySet(), Collections.emptyMap()
        );
        staffActor = new ActorPrincipal(
            staff.getId(), UUID.randomUUID(), Set.of(RoleCode.STAFF), Set.of("returns:process", "returns:read", "refunds:execute"),
            Map.of(facility.getId(), com.storagehub.domain.model.FacilityScopeLevel.OPERATE)
        );
        managerActor = new ActorPrincipal(
            manager.getId(), UUID.randomUUID(), Set.of(RoleCode.MANAGER), Set.of("rentals:update", "returns:read"),
            Map.of(facility.getId(), com.storagehub.domain.model.FacilityScopeLevel.MANAGE)
        );
        var sources = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        sources.addBean("returnFinance", returnFinance);
        ReflectionTestUtils.setField(service, "returnFinances", sources.getBeanProvider(com.storagehub.service.ledger.ReturnFinancialReconciliation.class));
        ReflectionTestUtils.setField(service, "maintenanceTaskRepository", maintenanceTaskRepository);
        ReflectionTestUtils.setField(service, "reservationPricingSnapshotRepository", pricingSnapshotRepository);
        ReflectionTestUtils.setField(service, "userFacilityScopeRepository", userFacilityScopeRepository);
        ReflectionTestUtils.setField(service, "settlementEntityManager", settlementEntityManager);
        var pallets = org.mockito.Mockito.mock(PalletInventoryService.class);
        org.mockito.Mockito.lenient().when(pallets.equipment(unit)).thenReturn(new com.storagehub.api.checkin.CheckInResponse.Equipment(1, 1, 2));
        ReflectionTestUtils.setField(service, "palletInventoryService", pallets);
        org.mockito.Mockito.lenient().when(rentalRepository.findByIdForUpdate(rental.getId())).thenReturn(Optional.of(rental));
        org.mockito.Mockito.lenient().when(returnFinance.deposit(rental)).thenReturn(new BigDecimal("2000000.00"));
        org.mockito.Mockito.lenient().when(returnFinance.capture(any(ReturnCase.class))).thenReturn(
            new com.storagehub.service.ledger.ReturnFinancialReconciliation.View(new BigDecimal("2000000.00"), BigDecimal.ZERO, BigDecimal.ZERO, 1L, BigDecimal.ZERO));
    }

    @Test
    void onsiteInventoryReturnKeepsCustomerOwnerAndRecordsActualStaffRequester() {
        rental.setStatus(RentalStatus.setup); rental.setInventoryPending(true);
        var request = new CustomerCreateReturnRequest(LocalDate.now(BusinessTime.ZONE).plusDays(1), "Khách chọn trả kho tại cơ sở", com.storagehub.domain.model.ReturnType.INVENTORY_MISMATCH);
        when(rentalRepository.findByIdForUpdate(rental.getId())).thenReturn(Optional.of(rental));
        when(returnCaseRepository.findByRental_Id(rental.getId())).thenReturn(Collections.emptyList());
        when(userRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> {
            ReturnCase rc = invocation.getArgument(0); ReflectionTestUtils.setField(rc, "id", UUID.randomUUID()); return rc;
        });
        service.createInventoryReturnAtSite(staffActor, rental.getId(), request);
        var captured = org.mockito.ArgumentCaptor.forClass(ReturnCase.class);
        verify(returnCaseRepository).save(captured.capture());
        assertThat(captured.getValue().getCustomer()).isEqualTo(customer);
        assertThat(captured.getValue().getRequestedBy()).isEqualTo(staff);
        assertThat(captured.getValue().getReturnType()).isEqualTo(com.storagehub.domain.model.ReturnType.INVENTORY_MISMATCH);
        verify(facilityScopeService).assertCanOperate(staffActor, facility.getId());
    }

    @Test
    void createCustomerReturnRequest_success() {
        CustomerCreateReturnRequest request = new CustomerCreateReturnRequest(
            LocalDate.now().plusDays(5),
            "Khong con nhu cau su dung"
        );

        when(rentalRepository.findByIdForUpdate(rental.getId())).thenReturn(Optional.of(rental));
        when(returnCaseRepository.findByRental_Id(rental.getId())).thenReturn(Collections.emptyList());
        when(userRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> {
            ReturnCase rc = invocation.getArgument(0);
            ReflectionTestUtils.setField(rc, "id", UUID.randomUUID());
            return rc;
        });

        ReturnCaseResponse response = service.createCustomerReturnRequest(customerActor, rental.getId(), request);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(ReturnCaseStatus.requested);
        assertThat(response.depositAmount()).isEqualByComparingTo(new BigDecimal("2000000.00"));
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.return_requested);
        verify(returnFinance).deposit(rental);
    }

    @Test
    void returnNoShowRecoveryStartsAfterReturnDayAndConfiguredGracePeriod() {
        LocalDate scheduledDate = LocalDate.now(BusinessTime.ZONE).plusDays(5);
        CustomerCreateReturnRequest request = new CustomerCreateReturnRequest(
            scheduledDate,
            "Khong con nhu cau su dung"
        );

        when(rentalRepository.findByIdForUpdate(rental.getId())).thenReturn(Optional.of(rental));
        when(returnCaseRepository.findByRental_Id(rental.getId())).thenReturn(Collections.emptyList());
        when(userRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> {
            ReturnCase rc = invocation.getArgument(0);
            ReflectionTestUtils.setField(rc, "id", UUID.randomUUID());
            return rc;
        });

        ReturnCaseResponse response = service.createCustomerReturnRequest(customerActor, rental.getId(), request);

        assertThat(response.recoveryEligibleAt()).isEqualTo(
            scheduledDate.plusDays(2).atStartOfDay(BusinessTime.ZONE).toInstant()
        );
    }

    @Test
    void inventoryMismatchReturnKeepsItsWorkflowMarker() {
        rental.setStatus(RentalStatus.setup);
        CustomerCreateReturnRequest sameDayRequest = new CustomerCreateReturnRequest(
            LocalDate.now(BusinessTime.ZONE), "Khách không tiếp nhận phương án kiểm kê", ReturnType.INVENTORY_MISMATCH
        );
        when(rentalRepository.findByIdForUpdate(rental.getId())).thenReturn(Optional.of(rental));
        when(returnCaseRepository.findByRental_Id(rental.getId())).thenReturn(Collections.emptyList());
        when(userRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        assertThatThrownBy(() -> service.createCustomerReturnRequest(customerActor, rental.getId(), sameDayRequest))
            .hasMessageContaining("sau ngày tạo đơn trả kho ít nhất 1 ngày");

        CustomerCreateReturnRequest request = new CustomerCreateReturnRequest(
            LocalDate.now(BusinessTime.ZONE).plusDays(1), "Khách không tiếp nhận phương án kiểm kê", ReturnType.INVENTORY_MISMATCH
        );
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> {
            ReturnCase rc = invocation.getArgument(0);
            ReflectionTestUtils.setField(rc, "id", UUID.randomUUID());
            return rc;
        });

        ReturnCaseResponse response = service.createCustomerReturnRequest(customerActor, rental.getId(), request);

        assertThat(response.returnType()).isEqualTo(ReturnType.INVENTORY_MISMATCH);
        assertThat(response.recoveryEligibleAt()).isNotNull();
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.return_requested);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void inventoryMismatchInspectionUsesActualUsageWithoutFixedEarlyTerminationFee(boolean collect) {
        var returnCase = setupReturn(ReturnCaseStatus.requested);
        rental.setStatus(RentalStatus.setup);
        rental.setInventoryPending(true);
        rental.setSetupExitReviewRequired(true);
        LocalDate receiptDate = LocalDate.now(BusinessTime.ZONE).minusDays(1);
        rental.setSetupStartedAt(receiptDate.atTime(10, 0).atZone(BusinessTime.ZONE).toInstant());
        rental.setSetupEndsAt(Instant.now().plus(24, java.time.temporal.ChronoUnit.HOURS));
        rental.setSetupCompletionDeadlineAt(Instant.now().plus(72, java.time.temporal.ChronoUnit.HOURS));
        rental.setMoveInCompletedAt(rental.getSetupStartedAt().plus(1, java.time.temporal.ChronoUnit.HOURS));
        returnCase.setReturnType(ReturnType.INVENTORY_MISMATCH);
        returnCase.setScheduledDate(LocalDate.now(BusinessTime.ZONE));

        var pricing = new com.storagehub.domain.model.ReservationPricingSnapshot();
        pricing.setMonthlyPrice(new BigDecimal("2000000"));
        pricing.setNetRentalAmount(new BigDecimal("6000000"));
        when(returnCaseRepository.findById(returnCase.getId())).thenReturn(Optional.of(returnCase));
        when(userRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(pricingSnapshotRepository.findByReservation_Id(rental.getReservation().getId())).thenReturn(Optional.of(pricing));
        when(maintenanceTaskRepository.findByRental_IdAndTaskTypeAndStatusIn(any(), any(), any())).thenReturn(Collections.emptyList());
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(returnFinance.capture(returnCase)).thenReturn(
            new com.storagehub.service.ledger.ReturnFinancialReconciliation.View(
                new BigDecimal("2000000"), collect ? new BigDecimal("9000000") : BigDecimal.ZERO, BigDecimal.ZERO, 1L, BigDecimal.ZERO
            )
        );

        ReturnCaseResponse response = service.performInspection(
            staffActor,
            returnCase.getId(),
            new ReturnInspectionRequest(
                InventoryMatch.excess,
                DamageClassification.no_damage,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "Hàng thực tế không phù hợp, khách yêu cầu trả kho",
                inspectionPhoto(returnCase),
                true,
                true,
                true,
                StorageUnitStatus.available,
                true
            )
        );

        assertThat(response.returnType()).isEqualTo(ReturnType.INVENTORY_MISMATCH);
        assertThat(response.status()).isEqualTo(ReturnCaseStatus.awaiting_customer_confirmation);
        assertThat(response.earlyTerminationFee()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.earlyTerminationPolicy().totalRentalCharge()).isEqualByComparingTo("133333");
        assertThat(response.earlyTerminationPolicy().rentalRefundAmount()).isEqualByComparingTo("5866667");
        // An inventory-mismatch return charges the verified usage (133,333) and refunds the
        // rest of the prepaid rent plus the unused deposit: netRefundAmount = 7,866,667
        // for both the refund and the extra-collection scenarios.
        assertThat(response.totalDeductions()).isEqualByComparingTo(collect ? "9133333" : "133333");
        // collect=false refunds the deposit plus the usage-adjusted rent; collect=true has an
        // extra 3,000,000 already-collected rent in the fixture, so its refundable remainder is lower.
        assertThat(response.netRefundAmount()).isEqualByComparingTo(collect ? "5866667" : "7866667");
        assertThat(response.amountDueFromCustomer()).isEqualByComparingTo(collect ? "7000000" : "0");
        assertThat(rental.isInventoryPending()).isFalse();
        when(returnCaseRepository.findByIdAndCustomer_Id(returnCase.getId(), customer.getId())).thenReturn(Optional.of(returnCase));
        var accepted = service.customerConfirmSettlement(customerActor, returnCase.getId(),
            new CustomerConfirmSettlementRequest(CustomerDecision.accepted, "Đồng ý bảng kê", response.settlementVersion()));
        assertThat(accepted.status()).isEqualTo(collect ? ReturnCaseStatus.payment_due : ReturnCaseStatus.refund_pending);
        if (collect) {
            var collection = new com.storagehub.api.returns.StaffReturnSettlementRequest(
                "CASH", null, UUID.randomUUID(), true, new BigDecimal("7000000"));
            var afterCollection = service.recordSettlement(staffActor, returnCase.getId(), collection);
            assertThat(afterCollection.status()).isEqualTo(ReturnCaseStatus.refund_pending);
            assertThat(rental.getStatus()).isNotEqualTo(RentalStatus.completed);
            verify(returnFinance, never()).settle(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
            when(returnFinance.matchesMismatchReceipt(returnCase, "CASH", "TM-" + returnCase.getId() + "-THU",
                collection.receiptFileId(), collection.amount())).thenReturn(true);
            assertThat(service.recordSettlement(staffActor, returnCase.getId(), collection).status()).isEqualTo(ReturnCaseStatus.refund_pending);
            when(returnFinance.hasCollectionReceipt(returnCase)).thenReturn(true);
            unit.setStatus(StorageUnitStatus.occupied);
            service.recordSettlement(staffActor, returnCase.getId(), new com.storagehub.api.returns.StaffReturnSettlementRequest(
                "BANK_TRANSFER", "REFUND-MISMATCH-SECOND-LEG", UUID.randomUUID(), true, new BigDecimal("5866667")));
            verify(returnFinance).settle(eq(returnCase), eq(staffActor.userId()), eq("REFUND-MISMATCH-SECOND-LEG"), eq(true));
        } else {
            service.staffCompleteRefund(staffActor, returnCase.getId(), new StaffCompleteRefundRequest(
                "REFUND-MISMATCH-TEST", "Hoàn tiền theo bảng kê", UUID.randomUUID(), "BANK_TRANSFER", true, new BigDecimal("7866667")));
        }
        assertThat(returnCase.getStatus()).isEqualTo(ReturnCaseStatus.completed);
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.completed);
        assertThat(unit.getStatus()).isEqualTo(collect ? StorageUnitStatus.occupied : StorageUnitStatus.maintenance);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void expiredReturnCreatesOneRecoveryTaskWithoutMovingGoodsAutomatically(boolean removal) {
        ReturnCase returnCase = new ReturnCase();
        ReflectionTestUtils.setField(returnCase, "id", UUID.randomUUID());
        returnCase.setRental(rental);
        returnCase.setFacility(facility);
        returnCase.setStorageUnit(unit);
        returnCase.setCustomer(customer);
        returnCase.setStatus(ReturnCaseStatus.requested);
        returnCase.setScheduledDate(LocalDate.now().minusDays(2));
        returnCase.setRecoveryEligibleAt(Instant.now().minusSeconds(60));
        if (removal) {
            returnCase.setReturnType(ReturnType.INVENTORY_MISMATCH);
            rental.setInventoryRemovalDeadlineAt(returnCase.getRecoveryEligibleAt());
        }

        when(returnCaseRepository.findByStatusInAndRecoveryEligibleAtLessThanEqualAndRecoveryTaskCreatedAtIsNull(
            any(), any(Instant.class))).thenReturn(List.of(returnCase));
        when(returnCaseRepository.findByIdForUpdate(returnCase.getId())).thenReturn(Optional.of(returnCase));
        when(maintenanceTaskRepository.findFirstByReturnCase_IdAndRecoveryTypeOrderByCreatedAtDesc(
            eq(returnCase.getId()), eq(com.storagehub.domain.model.MaintenanceRecoveryType.RETURN_NO_SHOW)))
            .thenReturn(Optional.empty());
        when(maintenanceTaskRepository.save(any(MaintenanceTask.class))).thenAnswer(invocation -> {
            MaintenanceTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
            return task;
        });
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int processed = service.escalateNoShowReturns();

        assertThat(processed).isEqualTo(1);
        assertThat(service.escalateNoShowReturns()).isZero();
        assertThat(returnCase.getStatus()).isEqualTo(ReturnCaseStatus.recovery_required);
        assertThat(returnCase.getNoShowAt()).isNotNull();
        assertThat(returnCase.getRecoveryTaskCreatedAt()).isNotNull();
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.occupied);
        if (removal) assertThat(returnCase.getRecoveryNotes()).contains("hết hạn đưa hàng sai lệch");
        verify(maintenanceTaskRepository).save(any(MaintenanceTask.class));
        verify(notificationService).createFacilityOperationsNotifications(
            eq(facility.getId()), eq(com.storagehub.domain.model.NotificationType.RETURN),
            eq(removal ? "Cần thu hồi hàng do quá hạn đưa hàng ra" : "Cần xử lý khách không đến trả kho"),
            any(String.class), eq(returnCase.getId())
        );
    }

    @Test
    void outdatedMismatchStatementCannotBeAcceptedOrDisputed() {
        var rc = setupReturn(ReturnCaseStatus.awaiting_customer_confirmation);
        rc.setReturnType(ReturnType.INVENTORY_MISMATCH);
        rc.setSettlementVersion(2);
        when(returnCaseRepository.findByIdAndCustomer_Id(rc.getId(), customer.getId())).thenReturn(Optional.of(rc));
        for (var decision : List.of(CustomerDecision.accepted, CustomerDecision.disputed)) {
            assertThatThrownBy(() -> service.customerConfirmSettlement(customerActor, rc.getId(),
                new CustomerConfirmSettlementRequest(decision, "Đã xem", 1L))).hasMessageContaining("Bảng kê đã thay đổi");
        }
        verify(returnFinance, never()).requireCurrent(any());
        assertThat(rc.isCustomerConfirmed()).isFalse();
    }

    @Test
    void mismatchReviewRepublishesStatementWhenFeeBreakdownChangesButTotalStaysEqual() {
        var rc = setupReturn(ReturnCaseStatus.disputed);
        rc.setReturnType(ReturnType.INVENTORY_MISMATCH);
        rc.setSettlementVersion(3);
        rc.setSettlementLedgerRevision(1L);
        rc.setDamageFee(new BigDecimal("200000"));
        rc.setOutstandingFee(BigDecimal.ZERO);
        rc.setRentalRefundCredit(new BigDecimal("1000000"));
        rc.setRentalRefundAmount(new BigDecimal("1000000"));
        rc.setTotalDeductions(new BigDecimal("300000"));
        rc.setNetRefundAmount(new BigDecimal("2700000"));
        rc.setAmountDueFromCustomer(BigDecimal.ZERO);
        rc.setEarlyTerminationCharge(new BigDecimal("100000"));
        rc.setCustomerConfirmed(true);
        Instant now = Instant.now();
        rc.setSetupReturnedAt(now);
        rental.setSetupStartedAt(now.minusSeconds(60));
        rental.setMoveInCompletedAt(now);
        rental.setSetupExitReviewRequired(true);
        var pricing = new com.storagehub.domain.model.ReservationPricingSnapshot();
        pricing.setMonthlyPrice(new BigDecimal("3000000"));
        pricing.setNetRentalAmount(new BigDecimal("1000000"));
        when(pricingSnapshotRepository.findByReservation_Id(rental.getReservation().getId())).thenReturn(Optional.of(pricing));
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        when(userRepository.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(returnCaseRepository.save(rc)).thenReturn(rc);
        when(returnFinance.capture(rc)).thenReturn(new com.storagehub.service.ledger.ReturnFinancialReconciliation.View(
            new BigDecimal("2000000"), new BigDecimal("100000"), BigDecimal.ZERO, 1L, BigDecimal.ZERO));
        var updated = service.managerReviewDispute(managerActor, rc.getId(), new ManagerReviewDisputeRequest(
            "Đối soát lại khoản phí", new BigDecimal("100000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, StorageUnitStatus.available));
        assertThat(updated.totalDeductions()).isEqualByComparingTo("300000");
        assertThat(updated.netRefundAmount()).isEqualByComparingTo("2700000");
        assertThat(updated.settlementVersion()).isEqualTo(4);
        assertThat(updated.status()).isEqualTo(ReturnCaseStatus.awaiting_customer_confirmation);
        assertThat(updated.customerConfirmed()).isFalse();
    }

    @Test
    void mismatchWithVerifiedHandoverCannotCreateRecovery() {
        ReturnCase record = setupReturn(ReturnCaseStatus.scheduled);
        record.setReturnType(ReturnType.INVENTORY_MISMATCH);
        Instant deadline = Instant.parse("2025-11-28T17:00:00Z");
        rental.setInventoryRemovalDeadlineAt(deadline);
        record.setRecoveryEligibleAt(deadline);
        rental.setActualReturnedAt(deadline.minusSeconds(1));
        when(returnCaseRepository.findByStatusInAndRecoveryEligibleAtLessThanEqualAndRecoveryTaskCreatedAtIsNull(
            any(), any(Instant.class))).thenReturn(List.of(record));
        when(returnCaseRepository.findByIdForUpdate(record.getId())).thenReturn(Optional.of(record));
        service.escalateNoShowReturns();
        verify(maintenanceTaskRepository, org.mockito.Mockito.never()).save(any(MaintenanceTask.class));
        assertThat(record.getStatus()).isEqualTo(ReturnCaseStatus.scheduled);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ReturnType.class, names = {"NORMAL", "INVENTORY_MISMATCH"})
    void noShowScanDoesNotTakeOverAnExpiredTimelySessionAwaitingReview(ReturnType type) {
        var rc = setupReturn(ReturnCaseStatus.scheduled);
        rc.setSetupExitRequested(type == ReturnType.INVENTORY_MISMATCH);
        rc.setReturnType(type);
        Instant due = Instant.now().minusSeconds(3600);
        rental.setInventoryRemovalDeadlineAt(due);
        rc.setReturnArrivedAt(due.minusSeconds(60));
        rc.setRecoveryEligibleAt(due);
        when(returnCaseRepository.findByStatusInAndRecoveryEligibleAtLessThanEqualAndRecoveryTaskCreatedAtIsNull(
            any(), any(Instant.class))).thenReturn(List.of(rc));
        when(returnCaseRepository.findByIdForUpdate(rc.getId())).thenReturn(Optional.of(rc));

        assertThat(service.escalateNoShowReturns()).isZero();
        assertThat(rc.getStatus()).isEqualTo(ReturnCaseStatus.scheduled);
        verify(maintenanceTaskRepository, org.mockito.Mockito.never()).save(any(MaintenanceTask.class));
    }

    @Test
    void reschedulingMismatchKeepsPublishedRemovalDeadline() {
        ReturnCase record = new ReturnCase();
        ReflectionTestUtils.setField(record, "id", UUID.randomUUID());
        record.setRental(rental);
        record.setFacility(facility);
        record.setStorageUnit(unit);
        record.setCustomer(customer);
        record.setReturnType(ReturnType.INVENTORY_MISMATCH);
        record.setSetupExitRequested(true);
        record.setStatus(ReturnCaseStatus.scheduled);
        LocalDate today = LocalDate.now(BusinessTime.ZONE);
        Instant deadline = today.plusDays(7).atStartOfDay(BusinessTime.ZONE).toInstant();
        rental.setInventoryRemovalDeadlineAt(deadline);
        record.setRecoveryEligibleAt(deadline);
        record.setScheduledDate(today.plusDays(1));
        when(returnCaseRepository.findOwnedForUpdate(record.getId(), customer.getId())).thenReturn(Optional.of(record));
        when(returnCaseRepository.saveAndFlush(record)).thenReturn(record);

        var request = new CustomerCreateReturnRequest(today.plusDays(6), "Đổi lịch dọn hàng");
        var updated = service.updateCustomerReturnRequest(customerActor, record.getId(), request);
        assertThat(updated.scheduledDate()).isEqualTo(request.scheduledDate());
        assertThat(updated.inventoryRemovalDeadlineAt()).isEqualTo(deadline);
        assertThat(updated.recoveryEligibleAt()).isEqualTo(deadline);

        assertThatThrownBy(() -> service.updateCustomerReturnRequest(customerActor, record.getId(),
            new CustomerCreateReturnRequest(today.plusDays(7), "Quá hạn")))
            .hasMessageContaining("trong thời hạn đưa hàng");
        assertThat(record.getScheduledDate()).isEqualTo(request.scheduledDate());
    }

    @Test
    void noShowReturnCannotBeInspectedBeforeGoodsAreMovedWithEvidence() {
        ReturnCase returnCase = new ReturnCase();
        ReflectionTestUtils.setField(returnCase, "id", UUID.randomUUID());
        returnCase.setRental(rental);
        returnCase.setFacility(facility);
        returnCase.setStorageUnit(unit);
        returnCase.setCustomer(customer);
        returnCase.setStatus(ReturnCaseStatus.recovery_required);
        returnCase.setDepositAmount(new BigDecimal("2000000.00"));
        returnCase.setScheduledDate(LocalDate.now().minusDays(2));

        MaintenanceTask recoveryTask = new MaintenanceTask();
        recoveryTask.setStatus(com.storagehub.domain.model.MaintenanceTaskStatus.in_progress);
        when(returnCaseRepository.findById(returnCase.getId())).thenReturn(Optional.of(returnCase));
        when(maintenanceTaskRepository.findFirstByReturnCase_IdAndRecoveryTypeOrderByCreatedAtDesc(
            eq(returnCase.getId()), eq(com.storagehub.domain.model.MaintenanceRecoveryType.RETURN_NO_SHOW)))
            .thenReturn(Optional.of(recoveryTask));

        ReturnInspectionRequest request = new ReturnInspectionRequest(
            InventoryMatch.match,
            DamageClassification.no_damage,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "",
            List.of(),
            false,
            false,
            false,
            StorageUnitStatus.maintenance,
            false
        );

        assertThatThrownBy(() -> service.performInspection(staffActor, returnCase.getId(), request))
            .hasMessageContaining("đưa toàn bộ hàng ra khỏi gian");
        org.mockito.Mockito.verify(returnFinance, org.mockito.Mockito.never()).capture(any());
        assertThat(returnCase.getStatus()).isEqualTo(ReturnCaseStatus.recovery_required);
    }

    @Test
    void noShowReturnRequiresHandoverLocationBeforeSettlementCanContinue() {
        ReturnCase returnCase = new ReturnCase();
        ReflectionTestUtils.setField(returnCase, "id", UUID.randomUUID());
        returnCase.setRental(rental);
        returnCase.setFacility(facility);
        returnCase.setStorageUnit(unit);
        returnCase.setCustomer(customer);
        returnCase.setStatus(ReturnCaseStatus.recovery_required);
        returnCase.setDepositAmount(new BigDecimal("2000000.00"));
        returnCase.setScheduledDate(LocalDate.now().minusDays(2));

        MaintenanceTask recoveryTask = new MaintenanceTask();
        recoveryTask.setStatus(com.storagehub.domain.model.MaintenanceTaskStatus.in_progress);
        when(returnCaseRepository.findById(returnCase.getId())).thenReturn(Optional.of(returnCase));
        when(maintenanceTaskRepository.findFirstByReturnCase_IdAndRecoveryTypeOrderByCreatedAtDesc(
            eq(returnCase.getId()), eq(com.storagehub.domain.model.MaintenanceRecoveryType.RETURN_NO_SHOW)))
            .thenReturn(Optional.of(recoveryTask));

        ReturnInspectionRequest request = new ReturnInspectionRequest(
            InventoryMatch.match,
            DamageClassification.no_damage,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "Đã đưa hàng vào khu lưu tạm",
            inspectionPhoto(returnCase),
            false,
            false,
            true,
            StorageUnitStatus.maintenance,
            true,
            "",
            false
        );

        assertThatThrownBy(() -> service.performInspection(staffActor, returnCase.getId(), request))
            .hasMessageContaining("khu vực lưu tạm");
        org.mockito.Mockito.verify(returnFinance, org.mockito.Mockito.never()).capture(any());
        assertThat(returnCase.getStatus()).isEqualTo(ReturnCaseStatus.recovery_required);
    }

    @Test
    void noShowReturnStoresHandoverLocationOnlyAfterEvidenceAndEmptyUnitAreConfirmed() {
        ReturnCase returnCase = new ReturnCase();
        ReflectionTestUtils.setField(returnCase, "id", UUID.randomUUID());
        returnCase.setRental(rental);
        returnCase.setFacility(facility);
        returnCase.setStorageUnit(unit);
        returnCase.setCustomer(customer);
        returnCase.setStatus(ReturnCaseStatus.recovery_required);
        returnCase.setDepositAmount(new BigDecimal("2000000.00"));
        returnCase.setScheduledDate(LocalDate.now().minusDays(2));

        MaintenanceTask recoveryTask = new MaintenanceTask();
        recoveryTask.setStatus(com.storagehub.domain.model.MaintenanceTaskStatus.in_progress);
        when(returnCaseRepository.findById(returnCase.getId())).thenReturn(Optional.of(returnCase));
        when(maintenanceTaskRepository.findFirstByReturnCase_IdAndRecoveryTypeOrderByCreatedAtDesc(
            eq(returnCase.getId()), eq(com.storagehub.domain.model.MaintenanceRecoveryType.RETURN_NO_SHOW)))
            .thenReturn(Optional.of(recoveryTask));
        when(userRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(maintenanceTaskRepository.save(any(MaintenanceTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User witness = new User();
        ReflectionTestUtils.setField(witness, "id", UUID.randomUUID());
        witness.setFullName("Nguyen Van Witness");
        witness.setStatus(com.storagehub.domain.model.UserStatus.ACTIVE);
        com.storagehub.domain.model.Role witnessRole = new com.storagehub.domain.model.Role();
        witnessRole.setCode(RoleCode.STAFF);
        witness.setRoles(Set.of(witnessRole));
        when(userRepository.findById(witness.getId())).thenReturn(Optional.of(witness));
        var witnessScope = new com.storagehub.domain.model.UserFacilityScope();
        witnessScope.setUser(witness);
        witnessScope.setFacility(facility);
        witnessScope.setScopeLevel(com.storagehub.domain.model.FacilityScopeLevel.OPERATE);
        when(userFacilityScopeRepository.findByUser_IdAndFacility_Id(witness.getId(), facility.getId()))
            .thenReturn(Optional.of(witnessScope));

        ReturnInspectionRequest request = new ReturnInspectionRequest(
            InventoryMatch.match,
            DamageClassification.no_damage,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "Đã kiểm kê và bàn giao hàng vào khu lưu tạm",
            inspectionPhoto(returnCase),
            false,
            false,
            true,
            StorageUnitStatus.maintenance,
            true,
            "Khu lưu tạm A, kệ 02, Staff B nhận bàn giao",
            false,
            witness.getId()
        );

        ReturnCaseResponse response = service.performInspection(staffActor, returnCase.getId(), request);

        assertThat(response.status()).isEqualTo(ReturnCaseStatus.awaiting_customer_confirmation);
        assertThat(response.warehouseEmptyVerified()).isTrue();
        assertThat(returnCase.getStatus()).isEqualTo(ReturnCaseStatus.awaiting_customer_confirmation);
        assertThat(returnCase.getRental().getStatus()).isEqualTo(RentalStatus.return_inspection);
        assertThat(recoveryTask.getStatus()).isEqualTo(com.storagehub.domain.model.MaintenanceTaskStatus.completed);
        assertThat(recoveryTask.getRecoveryHandoverLocation()).isEqualTo("Khu lưu tạm A, kệ 02, Staff B nhận bàn giao");
    }

    @Test
    void unavailableVerifiedDepositBlocksReturnWithoutInventingItFromMonthlyPrice() {
        when(rentalRepository.findByIdForUpdate(rental.getId())).thenReturn(Optional.of(rental));
        when(userRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(returnFinance.deposit(rental)).thenThrow(com.storagehub.common.api.ApiExceptions.conflict("Verified deposit unavailable"));
        assertThatThrownBy(() -> service.createCustomerReturnRequest(customerActor, rental.getId(),
            new CustomerCreateReturnRequest(LocalDate.now().plusDays(1), "Return")))
            .hasMessageContaining("Verified deposit unavailable");
        org.mockito.Mockito.verify(returnCaseRepository,org.mockito.Mockito.never()).save(any());
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.active);
    }

    @Test
    void customerCanEditOnlyAnUnprocessedOwnedReturn() {
        ReturnCase record = new ReturnCase();
        ReflectionTestUtils.setField(record, "id", UUID.randomUUID());
        record.setRental(rental); record.setFacility(facility); record.setStorageUnit(unit); record.setCustomer(customer);
        record.setStatus(ReturnCaseStatus.requested);
        LocalDate lastRentalDay = rental.getContractEndDate().minusDays(1);
        record.setScheduledDate(lastRentalDay);
        var periods = org.mockito.Mockito.mock(com.storagehub.service.rental.period.RentalPeriodResolver.class);
        when(periods.read(rental)).thenReturn(com.storagehub.service.rental.period.RentalPeriod.verified(
            rental.getId(), rental.getStartDate(), rental.getContractEndDate(),
            com.storagehub.service.rental.period.RentalPeriod.Convention.EXCLUSIVE, "test-confirmed-period"));
        ReflectionTestUtils.setField(service, "rentalPeriods", periods);
        when(returnCaseRepository.findOwnedForUpdate(record.getId(), customer.getId())).thenReturn(Optional.of(record));
        when(returnCaseRepository.saveAndFlush(record)).thenReturn(record);
        var result = service.updateCustomerReturnRequest(customerActor, record.getId(),
            new CustomerCreateReturnRequest(LocalDate.now().plusDays(3), "Chuyển địa điểm"));
        assertThat(result.scheduledDate()).isEqualTo(LocalDate.now().plusDays(3));
        assertThat(record.getCustomerNotes()).isEqualTo("Chuyển địa điểm");
        assertThat(record.getReturnType()).isEqualTo(com.storagehub.domain.model.ReturnType.EARLY_RETURN);
        assertThat(record.isActiveReturnRepricingRequired()).isTrue();
        service.updateCustomerReturnRequest(customerActor, record.getId(),
            new CustomerCreateReturnRequest(lastRentalDay, "Trả đúng hạn"));
        assertThat(record.getReturnType()).isEqualTo(com.storagehub.domain.model.ReturnType.NORMAL);
        assertThat(record.isActiveReturnRepricingRequired()).isFalse();
        record.setStatus(ReturnCaseStatus.scheduled);
        assertThatThrownBy(() -> service.updateCustomerReturnRequest(customerActor, record.getId(),
            new CustomerCreateReturnRequest(LocalDate.now().plusDays(4), "Đổi ngày"))).hasMessageContaining("được nhân viên xử lý");
    }

    @Test
    void returnEditCannotReadAnotherCustomersRequest() {
        UUID id = UUID.randomUUID();
        when(returnCaseRepository.findOwnedForUpdate(id, customer.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateCustomerReturnRequest(customerActor, id,
            new CustomerCreateReturnRequest(LocalDate.now().plusDays(1), "Trả kho"))).hasMessageContaining("của bạn");
    }

    @Test
    void performInspection_withNetRefund_calculatesCorrectly() {
        ReturnCase returnCase = new ReturnCase();
        setVerifiedReturnAttendance(returnCase);
        ReflectionTestUtils.setField(returnCase, "id", UUID.randomUUID());
        returnCase.setRental(rental);
        returnCase.setFacility(facility);
        returnCase.setStorageUnit(unit);
        returnCase.setCustomer(customer);
        returnCase.setRequestedBy(customer);
        returnCase.setStatus(ReturnCaseStatus.requested);
        returnCase.setDepositAmount(new BigDecimal("2000000.00"));
        returnCase.setScheduledDate(rental.getContractEndDate());

        ReturnInspectionRequest request = new ReturnInspectionRequest(
            InventoryMatch.match,
            DamageClassification.minor_damage,
            new BigDecimal("300000.00"), // damageFee
            new BigDecimal("100000.00"), // cleaningFee
            BigDecimal.ZERO, // lostItemFee
            BigDecimal.ZERO, // overdueFee
            BigDecimal.ZERO, // outstandingFee
            "Mặt sàn xước nhẹ, cần vệ sinh bụi",
            inspectionPhoto(returnCase),
            true, true, true,
            StorageUnitStatus.maintenance, true
        );

        when(returnCaseRepository.findById(returnCase.getId())).thenReturn(Optional.of(returnCase));
        when(userRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReturnCaseResponse response = service.performInspection(staffActor, returnCase.getId(), request);

        assertThat(response.status()).isEqualTo(ReturnCaseStatus.awaiting_customer_confirmation);
        assertThat(response.totalDeductions()).isEqualByComparingTo(new BigDecimal("400000.00"));
        assertThat(response.netRefundAmount()).isEqualByComparingTo(new BigDecimal("1600000.00"));
        assertThat(response.amountDueFromCustomer()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void performInspection_withAmountDue_calculatesCorrectly() {
        ReturnCase returnCase = new ReturnCase();
        setVerifiedReturnAttendance(returnCase);
        ReflectionTestUtils.setField(returnCase, "id", UUID.randomUUID());
        returnCase.setRental(rental);
        returnCase.setFacility(facility);
        returnCase.setStorageUnit(unit);
        returnCase.setCustomer(customer);
        returnCase.setRequestedBy(customer);
        returnCase.setStatus(ReturnCaseStatus.requested);
        returnCase.setDepositAmount(new BigDecimal("1000000.00")); // deposit is only 1M
        returnCase.setScheduledDate(LocalDate.now());

        ReturnInspectionRequest request = new ReturnInspectionRequest(
            InventoryMatch.match,
            DamageClassification.major_damage,
            new BigDecimal("1200000.00"), // damage
            new BigDecimal("200000.00"),  // cleaning
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "Cửa cuốn hỏng nặng",
            inspectionPhoto(returnCase),
            true, true, true,
            StorageUnitStatus.maintenance, true
        );

        when(returnCaseRepository.findById(returnCase.getId())).thenReturn(Optional.of(returnCase));
        when(userRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReturnCaseResponse response = service.performInspection(staffActor, returnCase.getId(), request);

        assertThat(response.totalDeductions()).isEqualByComparingTo(new BigDecimal("1400000.00"));
        assertThat(response.netRefundAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.amountDueFromCustomer()).isEqualByComparingTo(new BigDecimal("400000.00"));
        assertThat(response.proposedUnitStatus()).isEqualTo(StorageUnitStatus.maintenance);
    }

    @Test
    void customerConfirmSettlement_accepted_withRefund_setsRefundPending() {
        ReturnCase returnCase = new ReturnCase();
        ReflectionTestUtils.setField(returnCase, "id", UUID.randomUUID());
        returnCase.setRental(rental);
        returnCase.setFacility(facility);
        returnCase.setStorageUnit(unit);
        returnCase.setCustomer(customer);
        returnCase.setStatus(ReturnCaseStatus.awaiting_customer_confirmation);
        returnCase.setDepositAmount(new BigDecimal("2000000.00"));
        returnCase.setTotalDeductions(new BigDecimal("400000.00"));
        returnCase.setNetRefundAmount(new BigDecimal("1600000.00"));
        returnCase.setAmountDueFromCustomer(BigDecimal.ZERO);

        CustomerConfirmSettlementRequest request = new CustomerConfirmSettlementRequest(
            CustomerDecision.accepted,
            "Dong y voi quyet toan"
        );

        when(returnCaseRepository.findByIdAndCustomer_Id(returnCase.getId(), customer.getId()))
            .thenReturn(Optional.of(returnCase));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReturnCaseResponse response = service.customerConfirmSettlement(customerActor, returnCase.getId(), request);

        assertThat(response.status()).isEqualTo(ReturnCaseStatus.refund_pending);
        assertThat(response.customerConfirmed()).isTrue();
        assertThat(response.customerDecision()).isEqualTo(CustomerDecision.accepted);
        verify(returnFinance).requireCurrent(returnCase);
    }

    @Test
    void customerConfirmSettlement_includesUnusedRentalRefundInFinalAmount() {
        ReturnCase returnCase = new ReturnCase();
        ReflectionTestUtils.setField(returnCase, "id", UUID.randomUUID());
        returnCase.setRental(rental);
        returnCase.setFacility(facility);
        returnCase.setStorageUnit(unit);
        returnCase.setCustomer(customer);
        returnCase.setStatus(ReturnCaseStatus.awaiting_customer_confirmation);
        returnCase.setReturnType(ReturnType.NORMAL);
        returnCase.setRequestedAt(Instant.parse("2026-01-01T00:00:00Z"));
        returnCase.setScheduledDate(LocalDate.of(2026, 1, 1));
        returnCase.setInspectedAt(Instant.parse("2026-01-01T01:00:00Z"));
        returnCase.setActiveReturnRepricingRequired(true);
        returnCase.setDepositAmount(new BigDecimal("5500000.00"));
        returnCase.setCleaningFee(new BigDecimal("100000.00"));
        returnCase.setTotalDeductions(new BigDecimal("100000.00"));
        returnCase.setNetRefundAmount(new BigDecimal("5400000.00"));
        returnCase.setAmountDueFromCustomer(BigDecimal.ZERO);
        returnCase.setEarlyTerminationCharge(BigDecimal.ZERO);
        returnCase.setRentalRefundAmount(BigDecimal.ZERO);
        rental.setStartDate(LocalDate.of(2026, 1, 1));

        var pricing = new com.storagehub.domain.model.ReservationPricingSnapshot();
        pricing.setRentalMonths(3);
        pricing.setMonthlyPrice(new BigDecimal("4400000.00"));
        pricing.setNetRentalAmount(new BigDecimal("13200000.00"));
        pricing.setReservationDepositAmount(new BigDecimal("5500000.00"));
        pricing.setFreeSetupDays(3);
        pricing.setEarlyTerminationFeeCoefficient(BigDecimal.ONE);
        pricing.setEarlyTerminationPricingSnapshotJson(
            "[{\"rentalMonths\":3,\"monthlyPrice\":4400000,\"freeSetupDays\":3}]"
        );

        when(pricingSnapshotRepository.findByReservation_Id(rental.getReservation().getId()))
            .thenReturn(Optional.of(pricing));
        when(returnCaseRepository.findByIdAndCustomer_Id(returnCase.getId(), customer.getId()))
            .thenReturn(Optional.of(returnCase));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReturnCaseResponse response = service.customerConfirmSettlement(
            customerActor,
            returnCase.getId(),
            new CustomerConfirmSettlementRequest(CustomerDecision.accepted, "Đồng ý bảng kê")
        );

        assertThat(response.activeReturnPolicy().rentalRefundAmount()).isEqualByComparingTo("8800000.00");
        assertThat(returnCase.getNetRefundAmount()).isEqualByComparingTo("14200000.00");
        assertThat(response.netRefundAmount()).isEqualByComparingTo("14200000.00");
        assertThat(response.status()).isEqualTo(ReturnCaseStatus.refund_pending);
    }

    @Test
    void customerConfirmSettlement_disputed_setsDisputedStatus() {
        ReturnCase returnCase = new ReturnCase();
        ReflectionTestUtils.setField(returnCase, "id", UUID.randomUUID());
        returnCase.setRental(rental);
        returnCase.setFacility(facility);
        returnCase.setStorageUnit(unit);
        returnCase.setCustomer(customer);
        returnCase.setStatus(ReturnCaseStatus.awaiting_customer_confirmation);
        returnCase.setDepositAmount(new BigDecimal("2000000.00"));
        returnCase.setTotalDeductions(new BigDecimal("500000.00"));
        returnCase.setNetRefundAmount(new BigDecimal("1500000.00"));
        returnCase.setAmountDueFromCustomer(BigDecimal.ZERO);

        CustomerConfirmSettlementRequest request = new CustomerConfirmSettlementRequest(
            CustomerDecision.disputed,
            "Phi ve sinh 500k qua cao, kho da duoc don sach"
        );

        when(returnCaseRepository.findByIdAndCustomer_Id(returnCase.getId(), customer.getId()))
            .thenReturn(Optional.of(returnCase));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReturnCaseResponse response = service.customerConfirmSettlement(customerActor, returnCase.getId(), request);

        assertThat(response.status()).isEqualTo(ReturnCaseStatus.disputed);
        assertThat(response.customerDecision()).isEqualTo(CustomerDecision.disputed);
        assertThat(response.customerDecisionNote()).contains("Phi ve sinh 500k qua cao");
    }

    @org.junit.jupiter.params.ParameterizedTest
    // The non-setup branch of calculateSettlement() refunds deposit + rental credit - fees, so the
    // 4,000,000 rental credit adds to the 2,000,000 deposit before the 100,000 fee is deducted.
    @org.junit.jupiter.params.provider.CsvSource({"0,0,1900000", "8000000,4000000,5900000"})
    void managerReviewDispute_recalculatesAndResolves(String rentalCharge, String rentalRefund, String expectedRefund) {
        ReturnCase returnCase = new ReturnCase();
        ReflectionTestUtils.setField(returnCase, "id", UUID.randomUUID());
        returnCase.setRental(rental);
        returnCase.setFacility(facility);
        returnCase.setStorageUnit(unit);
        returnCase.setCustomer(customer);
        returnCase.setStatus(ReturnCaseStatus.disputed);
        returnCase.setDepositAmount(new BigDecimal("2000000.00"));
        returnCase.setTotalDeductions(new BigDecimal("500000.00"));
        returnCase.setNetRefundAmount(new BigDecimal("1500000.00"));
        returnCase.setAmountDueFromCustomer(BigDecimal.ZERO);
        returnCase.setEarlyTerminationCharge(new BigDecimal(rentalCharge));
        returnCase.setRentalRefundAmount(new BigDecimal(rentalRefund));

        // Manager reduces cleaning fee to 100k
        ManagerReviewDisputeRequest request = new ManagerReviewDisputeRequest(
            "Dong y giam phi ve sinh xuong 100k sau khi kiem tra lai video",
            BigDecimal.ZERO,             // damageFee
            new BigDecimal("100000.00"), // cleaningFee
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            StorageUnitStatus.available
        );

        when(returnCaseRepository.findById(returnCase.getId())).thenReturn(Optional.of(returnCase));
        when(userRepository.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReturnCaseResponse response = service.managerReviewDispute(managerActor, returnCase.getId(), request);

        assertThat(response.totalDeductions()).isEqualByComparingTo(new BigDecimal("100000.00"));
        assertThat(response.netRefundAmount()).isEqualByComparingTo(expectedRefund);
        assertThat(response.amountDueFromCustomer()).isZero();
        // The dispute review changed the approved amounts, so the previous customer
        // confirmation no longer applies: the case must return to the customer before any
        // refund is released, never jump straight to refund_pending.
        assertThat(response.status()).isEqualTo(ReturnCaseStatus.awaiting_customer_confirmation);
        assertThat(response.customerConfirmed()).isFalse();
        assertThat(response.reviewedByName()).isEqualTo("Le Van Manager");
    }

    @Test
    void managerReviewDispute_usesActiveRentalRefundInFinalSettlement() {
        ReturnCase returnCase = new ReturnCase();
        ReflectionTestUtils.setField(returnCase, "id", UUID.randomUUID());
        returnCase.setRental(rental);
        returnCase.setFacility(facility);
        returnCase.setStorageUnit(unit);
        returnCase.setCustomer(customer);
        returnCase.setStatus(ReturnCaseStatus.disputed);
        returnCase.setReturnType(ReturnType.EARLY_RETURN);
        returnCase.setRequestedAt(Instant.parse("2026-01-01T00:00:00Z"));
        returnCase.setScheduledDate(LocalDate.of(2026, 1, 1));
        returnCase.setInspectedAt(Instant.parse("2026-01-01T01:00:00Z"));
        returnCase.setActiveReturnRepricingRequired(true);
        returnCase.setDepositAmount(new BigDecimal("5500000.00"));
        returnCase.setCleaningFee(new BigDecimal("100000.00"));
        returnCase.setTotalDeductions(new BigDecimal("100000.00"));
        returnCase.setNetRefundAmount(new BigDecimal("5400000.00"));
        returnCase.setAmountDueFromCustomer(BigDecimal.ZERO);
        returnCase.setEarlyTerminationCharge(BigDecimal.ZERO);
        returnCase.setRentalRefundAmount(BigDecimal.ZERO);
        rental.setStartDate(LocalDate.of(2026, 1, 1));

        var pricing = new com.storagehub.domain.model.ReservationPricingSnapshot();
        pricing.setRentalMonths(3);
        pricing.setMonthlyPrice(new BigDecimal("4400000.00"));
        pricing.setNetRentalAmount(new BigDecimal("13200000.00"));
        pricing.setReservationDepositAmount(new BigDecimal("5500000.00"));
        pricing.setFreeSetupDays(3);
        pricing.setEarlyTerminationFeeCoefficient(BigDecimal.ONE);
        pricing.setEarlyTerminationPricingSnapshotJson(
            "[{\"rentalMonths\":3,\"monthlyPrice\":4400000,\"freeSetupDays\":3}]"
        );

        when(pricingSnapshotRepository.findByReservation_Id(rental.getReservation().getId()))
            .thenReturn(Optional.of(pricing));
        when(returnCaseRepository.findById(returnCase.getId())).thenReturn(Optional.of(returnCase));
        when(userRepository.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReturnCaseResponse response = service.managerReviewDispute(
            managerActor,
            returnCase.getId(),
            new ManagerReviewDisputeRequest(
                "Xác nhận lại khoản thuê chưa sử dụng theo bảng giá đã chốt",
                BigDecimal.ZERO,
                new BigDecimal("100000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                StorageUnitStatus.available
            )
        );

        assertThat(response.activeReturnPolicy().rentalRefundAmount()).isEqualByComparingTo("8800000.00");
        assertThat(returnCase.getRentalRefundAmount()).isEqualByComparingTo("8800000.00");
        assertThat(response.netRefundAmount()).isEqualByComparingTo("14200000.00");
        assertThat(response.status()).isEqualTo(ReturnCaseStatus.awaiting_customer_confirmation);
    }

    @Test
    void staffConfirmSettlement_cannotBypassCustomerConsent() {
        ReturnCase returnCase = new ReturnCase();
        ReflectionTestUtils.setField(returnCase, "id", UUID.randomUUID());
        returnCase.setRental(rental);
        returnCase.setFacility(facility);
        returnCase.setStorageUnit(unit);
        returnCase.setCustomer(customer);
        returnCase.setStatus(ReturnCaseStatus.awaiting_customer_confirmation);
        returnCase.setDepositAmount(new BigDecimal("2000000.00"));
        returnCase.setNetRefundAmount(new BigDecimal("1900000.00"));
        returnCase.setAmountDueFromCustomer(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.staffConfirmSettlement(
            staffActor,
            returnCase.getId(),
            new CustomerConfirmSettlementRequest(CustomerDecision.accepted, "Khach xac nhan tai quay")
        ))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("Customer phải trực tiếp xác nhận");
        assertThat(returnCase.isCustomerConfirmed()).isFalse();
    }

    @Test
    void linkedRecoverySettlementDoesNotReleaseUnitWhenCustomerConfirmsZeroBalance() {
        ReturnCase rc=new ReturnCase();ReflectionTestUtils.setField(rc,"id",UUID.randomUUID());
        rc.setRental(rental);rc.setFacility(facility);rc.setStorageUnit(unit);rc.setCustomer(customer);
        rc.setStatus(ReturnCaseStatus.awaiting_customer_confirmation);rental.setStatus(RentalStatus.return_inspection);
        var guard=org.mockito.Mockito.mock(com.storagehub.service.overdue.RecoverySettlementGuard.class);
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();beans.addBean("guard",guard);
        ReflectionTestUtils.setField(service,"recoveryGuards",beans.getBeanProvider(com.storagehub.service.overdue.RecoverySettlementGuard.class));
        when(guard.linked(rc)).thenReturn(true);
        when(returnCaseRepository.findByIdAndCustomer_Id(rc.getId(),customer.getId())).thenReturn(Optional.of(rc));
        when(returnCaseRepository.save(rc)).thenReturn(rc);
        var response=service.customerConfirmSettlement(customerActor,rc.getId(),new CustomerConfirmSettlementRequest(CustomerDecision.accepted,"Accepted"));
        assertThat(response.status()).isEqualTo(ReturnCaseStatus.completed);
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.return_inspection);assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.occupied);
        verify(rentalRepository).findByIdForUpdate(rental.getId());
        org.mockito.Mockito.verify(rentalRepository,org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(storageUnitRepository);
    }

    @Test
    void staffCompleteRefund_finalizesRentalAndStorageUnit() {
        ReturnCase returnCase = new ReturnCase();
        ReflectionTestUtils.setField(returnCase, "id", UUID.randomUUID());
        returnCase.setRental(rental);
        returnCase.setFacility(facility);
        returnCase.setStorageUnit(unit);
        returnCase.setCustomer(customer);
        returnCase.setStatus(ReturnCaseStatus.refund_pending);
        returnCase.setDepositAmount(new BigDecimal("2000000.00"));
        returnCase.setNetRefundAmount(new BigDecimal("1900000.00"));
        returnCase.setProposedUnitStatus(StorageUnitStatus.available);

        StaffCompleteRefundRequest request = new StaffCompleteRefundRequest(
            "BANK-REF-998877",
            "Da chuyen khoan tra khach qua Vietcombank", UUID.randomUUID(), "BANK_TRANSFER", true,
            new BigDecimal("1900000")
        );

        when(returnCaseRepository.findById(returnCase.getId())).thenReturn(Optional.of(returnCase));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReturnCaseResponse response = service.staffCompleteRefund(staffActor, returnCase.getId(), request);

        assertThat(response.status()).isEqualTo(ReturnCaseStatus.completed);
        assertThat(response.settlementPaymentId()).isEqualTo("BANK-REF-998877");
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.completed);
        assertThat(unit.getStatus()).isEqualTo(StorageUnitStatus.available);
        verify(rentalRepository).save(rental);
        verify(storageUnitRepository).save(unit);
        verify(returnFinance).receipt(staffActor, returnCase, request.paymentMethod(), request.transactionReference(), request.receiptFileId());
        verify(returnFinance).settle(returnCase, staffActor.userId(), request.transactionReference(), true);
    }

    @Test
    void retryOfAnOpenRequestReturnsTheSameCaseAfterRentalStatusChanged() {
        var rc = setupReturn(ReturnCaseStatus.requested);
        rental.setStatus(RentalStatus.return_requested);
        when(returnCaseRepository.findByRental_Id(rental.getId())).thenReturn(List.of(rc));
        var response = service.createCustomerReturnRequest(customerActor, rental.getId(),
            new CustomerCreateReturnRequest(LocalDate.now(), "Retry"));
        assertThat(response.id()).isEqualTo(rc.getId());
        org.mockito.Mockito.verify(returnCaseRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verify(returnFinance, org.mockito.Mockito.never()).deposit(any());
        org.mockito.Mockito.verify(returnFinance, org.mockito.Mockito.never()).capture(any());
    }

    @Test
    void setupInspectionRequiresAnEmptyUnitBeforeAnyFinancialCapture() {
        var rc = setupReturn(ReturnCaseStatus.requested);
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        assertThatThrownBy(() -> service.performInspection(staffActor, rc.getId(), setupInspection(false)))
            .hasMessageContaining("kho trống");
        org.mockito.Mockito.verify(returnFinance, org.mockito.Mockito.never()).capture(any());
        assertThat(rc.getSetupReturnedAt()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    // V2 setup-exit policy retains 20% of the accepted net rental (1,200,000) and refunds 80%
    // (4,800,000). The security deposit only absorbs the verified inspection fees: any fee excess
    // becomes an amount due instead of silently eating into the 80% rental refund.
    @org.junit.jupiter.params.provider.CsvSource({"300000,300000,6500000,0", "8000000,8000000,4800000,6000000"})
    void setupRefundIncludesVerifiedRentAndOneMonthFeeWithoutCountingPrepaymentAgain(
        String damage, String deductions, String refund, String due) {
        var rc = setupReturn(ReturnCaseStatus.requested);
        rc.setRequestedAt(Instant.now().plusSeconds(7 * 86_400L));
        rental.setSetupStartedAt(Instant.now().minusSeconds(3600));
        rental.setSetupEndsAt(Instant.now().plusSeconds(86400));
        var pricing = new com.storagehub.domain.model.ReservationPricingSnapshot();
        pricing.setMonthlyPrice(new BigDecimal("2000000"));
        pricing.setNetRentalAmount(new BigDecimal("6000000"));
        pricing.setSetupEarlyExitPolicyVersion(SetupEarlyExitPolicy.VERSION);
        pricing.setSetupEarlyExitFee(new BigDecimal("1200000"));
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        when(userRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(returnCaseRepository.save(rc)).thenReturn(rc);
        when(returnFinance.capture(rc)).thenAnswer(invocation -> {
            SetupEarlyExitPolicy.capture(rc, pricing);
            return new com.storagehub.service.ledger.ReturnFinancialReconciliation.View(rc.getDepositAmount(),
                BigDecimal.ZERO, BigDecimal.ZERO, 1L, BigDecimal.ZERO);
        });
        var fileId = UUID.randomUUID();
        var asset = new com.storagehub.domain.model.FileAsset();
        asset.setContentType("image/jpeg"); asset.setEntityType("RETURN_CASE"); asset.setEntityId(rc.getId());
        asset.setStatus(com.storagehub.domain.model.FileAssetStatus.ACTIVE);
        when(settlementEntityManager.find(com.storagehub.domain.model.FileAsset.class, fileId)).thenReturn(asset);
        var inspection = new ReturnInspectionRequest(InventoryMatch.match, DamageClassification.minor_damage,
            new BigDecimal(damage), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            "Vết xước khi trả kho, có ảnh đối chiếu", List.of(fileId.toString()), true, true, true, StorageUnitStatus.maintenance, true);
        var response = service.performInspection(staffActor, rc.getId(), inspection);
        assertThat(response.rentalRefundCredit()).isEqualByComparingTo("4800000");
        assertThat(response.earlyTerminationFee()).isEqualByComparingTo("1200000");
        assertThat(response.totalDeductions()).isEqualByComparingTo(deductions);
        assertThat(response.netRefundAmount()).isEqualByComparingTo(refund);
        assertThat(response.amountDueFromCustomer()).isEqualByComparingTo(due);
        assertThat(response.setupReturnedAt().getNano() % 1000).isZero();
    }

    @Test
    void setupDisputeReviewDoesNotDeductRetainedRentTwice() {
        var rc = setupReturn(ReturnCaseStatus.disputed);
        rc.setSetupEarlyExit(true);
        rc.setDamageFee(new BigDecimal("500000"));
        rc.setCleaningFee(BigDecimal.ZERO);
        rc.setLostItemFee(BigDecimal.ZERO);
        rc.setEarlyTerminationFee(new BigDecimal("1200000"));
        rc.setRentalRefundCredit(new BigDecimal("4800000"));
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        when(userRepository.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(returnCaseRepository.save(rc)).thenReturn(rc);
        var response = service.managerReviewDispute(managerActor, rc.getId(),
            new ManagerReviewDisputeRequest("Giảm phí theo bằng chứng kiểm kê", new BigDecimal("300000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, StorageUnitStatus.available));
        // V2: the 1,200,000 retained rental (20%) already lives inside earlyTerminationCharge and
        // rentalRefundCredit is the 80% refund, so the statement only deducts the verified fees;
        // the refund is 4,800,000 rental credit + the 1,700,000 deposit left after the 300,000 fee.
        assertThat(response.totalDeductions()).isEqualByComparingTo("300000");
        assertThat(response.netRefundAmount()).isEqualByComparingTo("6500000");
        assertThat(response.amountDueFromCustomer()).isZero();
        // Amounts changed during the dispute review, so the customer must confirm the new
        // statement again instead of the refund being released straight away.
        assertThat(response.status()).isEqualTo(ReturnCaseStatus.awaiting_customer_confirmation);
        assertThat(response.customerConfirmed()).isFalse();
    }

    @Test
    void financeFailureCannotCompleteRefundOrReleaseRental() {
        var rc = setupReturn(ReturnCaseStatus.refund_pending);
        rc.setNetRefundAmount(new BigDecimal("5700000"));
        var file = UUID.randomUUID();
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        org.mockito.Mockito.doThrow(com.storagehub.common.api.ApiExceptions.conflict("Receipt proof unavailable"))
            .when(returnFinance).receipt(staffActor, rc, "CASH", "TM-" + rc.getId(), file);
        assertThatThrownBy(() -> service.staffCompleteRefund(staffActor, rc.getId(),
            new StaffCompleteRefundRequest(null, "Refund", file, "CASH", true, new BigDecimal("5700000"))))
            .hasMessageContaining("Receipt proof unavailable");
        assertThat(rc.getStatus()).isEqualTo(ReturnCaseStatus.refund_pending);
        assertThat(rc.getCompletedAt()).isNull();
        org.mockito.Mockito.verify(returnFinance, org.mockito.Mockito.never()).settle(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.verifyNoInteractions(storageUnitRepository);
    }

    @Test
    void wrongAmountOrMissingConfirmationCannotRecordAPayout() {
        var rc = setupReturn(ReturnCaseStatus.refund_pending);
        rc.setNetRefundAmount(new BigDecimal("5700000"));
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        assertThatThrownBy(() -> service.recordSettlement(staffActor, rc.getId(),
            new com.storagehub.api.returns.StaffReturnSettlementRequest("CASH", "WRONG", UUID.randomUUID(), true, new BigDecimal("6000000"))))
            .hasMessageContaining("không khớp");
        assertThatThrownBy(() -> service.staffCompleteRefund(staffActor, rc.getId(), new StaffCompleteRefundRequest("REFERENCE-ONLY", "Refund")))
            .hasMessageContaining("xác nhận số tiền");
        assertThat(rc.getStatus()).isEqualTo(ReturnCaseStatus.refund_pending);
        org.mockito.Mockito.verifyNoInteractions(returnFinance);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ReturnCaseStatus.class, names = {"payment_due", "refund_pending"})
    void cashSettlementGeneratesOneReceiptCodeForRepeatedSubmissions(ReturnCaseStatus status) {
        var rc = setupReturn(status);
        var amount = new BigDecimal("500000");
        if (status == ReturnCaseStatus.payment_due) {
            rc.setAmountDueFromCustomer(amount);
        } else {
            rc.setAmountDueFromCustomer(BigDecimal.ZERO);
            rc.setNetRefundAmount(amount);
        }
        var file = UUID.randomUUID();
        String reference = "TM-" + rc.getId();
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        var request = new com.storagehub.api.returns.StaffReturnSettlementRequest("CASH", null, file, true, amount);

        assertThat(service.recordSettlement(staffActor, rc.getId(), request).settlementPaymentId())
            .isEqualTo(reference);

        when(returnFinance.recordedReceiptMatches(rc, "CASH", reference, file)).thenReturn(true);
        assertThat(service.recordSettlement(staffActor, rc.getId(), request).settlementPaymentId())
            .isEqualTo(reference);
        verify(returnFinance).receipt(staffActor, rc, "CASH", reference, file);
        verify(returnFinance).settle(rc, staffActor.userId(), reference, true);
    }

    @Test
    void repeatedVerifiedReceiptReturnsCompletedResultWithoutAnotherPayout() {
        var rc = setupReturn(ReturnCaseStatus.completed);
        rc.setNetRefundAmount(new BigDecimal("5700000"));
        var file = UUID.randomUUID();
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        when(returnFinance.recordedReceiptMatches(rc, "CASH", "SAME-REFUND", file)).thenReturn(true);
        var response = service.recordSettlement(staffActor, rc.getId(),
            new com.storagehub.api.returns.StaffReturnSettlementRequest("CASH", "SAME-REFUND", file, true, new BigDecimal("5700000")));
        assertThat(response.status()).isEqualTo(ReturnCaseStatus.completed);
        org.mockito.Mockito.verify(returnFinance, org.mockito.Mockito.never()).receipt(any(), any(), any(), any(), any());
        org.mockito.Mockito.verify(returnFinance, org.mockito.Mockito.never()).settle(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.verifyNoInteractions(storageUnitRepository);
    }

    @Test
    void customerPaymentDeclarationDoesNotSettleTheReturn() {
        var rc = setupReturn(ReturnCaseStatus.payment_due);
        assertThatThrownBy(() -> service.customerPaySettlement(customerActor, rc.getId(),
            new CustomerPaySettlementRequest("CASH", "Customer declared paid"))).hasMessageContaining("thanh toán tại cơ sở");
        assertThat(rc.getStatus()).isEqualTo(ReturnCaseStatus.payment_due);
        assertThat(rc.getSettlementPaidAt()).isNull();
        org.mockito.Mockito.verifyNoInteractions(returnFinance, storageUnitRepository);
    }

    @Test
    void completedSettlementRejectsChangedAmountOrReceiptWithoutAnotherPayout() {
        var rc = setupReturn(ReturnCaseStatus.completed);
        rc.setNetRefundAmount(new BigDecimal("5700000"));
        var file = UUID.randomUUID();
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        assertThatThrownBy(() -> service.recordSettlement(staffActor, rc.getId(),
            new com.storagehub.api.returns.StaffReturnSettlementRequest("CASH", "SAME-REFUND", file, true, new BigDecimal("6000000"))))
            .hasMessageContaining("không khớp");
        org.mockito.Mockito.verifyNoInteractions(returnFinance);
        assertThatThrownBy(() -> service.recordSettlement(staffActor, rc.getId(),
            new com.storagehub.api.returns.StaffReturnSettlementRequest("CASH", "DIFFERENT-REFUND", file, true, new BigDecimal("5700000"))))
            .hasMessageContaining("không khớp");
        org.mockito.Mockito.verify(returnFinance, org.mockito.Mockito.never()).receipt(any(), any(), any(), any(), any());
        org.mockito.Mockito.verify(returnFinance, org.mockito.Mockito.never()).settle(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.verifyNoInteractions(storageUnitRepository);
        org.mockito.Mockito.verify(returnCaseRepository, org.mockito.Mockito.never()).save(any());
    }

    private ReturnCase setupReturn(ReturnCaseStatus status) {
        var rc = new ReturnCase();
        ReflectionTestUtils.setField(rc, "id", UUID.randomUUID());
        rc.setRental(rental); rc.setFacility(facility); rc.setStorageUnit(unit); rc.setCustomer(customer);
        rc.setStatus(status); rc.setSetupExitRequested(true); rc.setDepositAmount(new BigDecimal("2000000"));
        rc.setScheduledDate(LocalDate.now());
        return rc;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ReturnType.class, names = {"NORMAL", "INVENTORY_MISMATCH"})
    void returnAttendanceCountsEachAttemptOnceAndRequiresManagerAfterTwoFailures(ReturnType type) {
        var rc = setupReturn(ReturnCaseStatus.scheduled);
        rc.setSetupExitRequested(type == ReturnType.INVENTORY_MISMATCH);
        rc.setReturnType(type);
        rental.setInventoryRemovalDeadlineAt(Instant.now().plusSeconds(60));
        rental.getReservation().setIdentityDocumentNumber("TEST99999");
        rc.setReturnArrivedAt(Instant.now());
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));

        UUID first = UUID.randomUUID();
        assertThat(service.verifyReturnIdentity(staffActor, rc.getId(), first, "TEST11111").attendance().attempts()).isOne();
        assertThat(service.verifyReturnIdentity(staffActor, rc.getId(), first, "TEST99999").attendance().verifiedAt()).isNull();
        var failed = service.verifyReturnIdentity(staffActor, rc.getId(), UUID.randomUUID(), "TEST22222").attendance();
        verify(notificationService).notifyManagersOfReturnIdentityReview(rc);
        assertThat(failed.attempts()).isEqualTo(2);
        assertThat(failed.reviewStartsAt()).isNotNull();
        assertThatThrownBy(() -> service.verifyReturnIdentity(staffActor, rc.getId(), UUID.randomUUID(), "TEST99999"))
            .hasMessageContaining("đã kết thúc");
        assertThatThrownBy(() -> service.reviewReturnArrival(managerActor, rc.getId(), true, "Đã đối soát"))
            .hasMessageContaining("danh tính");
        service.reviewReturnArrival(managerActor, rc.getId(), false, "Giấy tờ không khớp");
        assertThat(ReturnAttendancePolicy.protectsFromRecovery(rc)).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ReturnType.class, names = {"NORMAL", "INVENTORY_MISMATCH"})
    void managerReopensOnlyOneVerificationWindowForAssignedStaff(ReturnType type) {
        var rc = setupReturn(ReturnCaseStatus.scheduled);
        rc.setReturnType(type);
        rc.setSetupExitRequested(type == ReturnType.INVENTORY_MISMATCH);
        Instant arrivedAt = Instant.now().minusSeconds(1801);
        rc.setReturnArrivedAt(arrivedAt);
        rc.setRecoveryEligibleAt(arrivedAt.plusSeconds(60));
        rental.setInventoryRemovalDeadlineAt(arrivedAt.plusSeconds(60));
        UUID evidence = UUID.randomUUID();
        rc.setReturnArrivalEvidenceId(evidence);
        rc.setReturnFirstAttemptId(UUID.randomUUID());
        rc.setReturnSecondAttemptId(UUID.randomUUID());
        rc.setReturnSecondAttemptAt(arrivedAt.plusSeconds(90));
        rental.getReservation().setIdentityDocumentNumber("TEST99999");
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        when(userRepository.findEligibleStaff(eq(com.storagehub.domain.model.UserStatus.ACTIVE), eq(RoleCode.STAFF),
            eq(facility.getId()), any())).thenReturn(List.of(staff));

        assertThatThrownBy(() -> service.reopenReturnVerification(staffActor, rc.getId(), staff.getId(), "Khách mang giấy tờ gốc"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        assertThatThrownBy(() -> service.reopenReturnVerification(managerActor, rc.getId(), UUID.randomUUID(), "Khách có mặt"))
            .hasMessageContaining("nhân viên phụ trách");
        var reopened = service.reopenReturnVerification(managerActor, rc.getId(), staff.getId(), "Khách mang giấy tờ gốc").attendance();
        Instant session = reopened.reverificationStartedAt();
        assertThat(reopened.arrivedAt()).isEqualTo(arrivedAt);
        assertThat(reopened.evidenceId()).isEqualTo(evidence);
        assertThat(reopened.attempts()).isZero();
        assertThat(reopened.expiresAt()).isEqualTo(session.plusSeconds(1800));
        assertThat(ReturnAttendancePolicy.protectsFromRecovery(rc)).isTrue();

        assertThatThrownBy(() -> service.verifyReturnIdentity(staffActor, rc.getId(), UUID.randomUUID(), "TEST99999"))
            .hasMessageContaining("Phiên xác minh đã thay đổi");
        var otherStaff = new ActorPrincipal(UUID.randomUUID(), staffActor.sessionId(), staffActor.roles(),
            staffActor.permissions(), staffActor.facilityScopes());
        assertThatThrownBy(() -> service.verifyReturnIdentity(otherStaff, rc.getId(), UUID.randomUUID(), "TEST99999", session))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        service.verifyReturnIdentity(staffActor, rc.getId(), UUID.randomUUID(), "TEST11111", session);
        var failed = service.verifyReturnIdentity(staffActor, rc.getId(), UUID.randomUUID(), "TEST22222", session).attendance();
        assertThat(failed.reviewStartsAt()).isNotNull();
        assertThat(failed.attempts()).isEqualTo(2);
        assertThatThrownBy(() -> service.reopenReturnVerification(managerActor, rc.getId(), staff.getId(), "Yêu cầu thử thêm"))
            .hasMessageContaining("Chỉ được mở lại một lần");
    }

    @Test
    void managerSetsHandoverTimeAndInspectionOpensOnlyAtThatTime() {
        var rc = setupReturn(ReturnCaseStatus.scheduled);
        rc.setReturnType(ReturnType.NORMAL);
        rc.setSetupExitRequested(false);
        rc.setReturnArrivedAt(Instant.now().minusSeconds(1900));
        rc.setReturnArrivalEvidenceId(UUID.randomUUID());
        rental.getReservation().setIdentityDocumentNumber("TEST99999");
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        when(userRepository.findEligibleStaff(eq(com.storagehub.domain.model.UserStatus.ACTIVE), eq(RoleCode.STAFF),
            eq(facility.getId()), any())).thenReturn(List.of(staff));
        var calendar = org.mockito.Mockito.mock(WorkflowBusinessCalendar.class);
        var schedule = WorkflowBusinessCalendar.parse(WorkflowBusinessCalendar.DEFAULT);
        when(calendar.current()).thenReturn(schedule);
        ReflectionTestUtils.setField(service, "recoveryCalendar", calendar);

        var session = service.reopenReturnVerification(managerActor, rc.getId(), staff.getId(), "Đối chiếu giấy tờ gốc").attendance();
        assertThat(service.verifyReturnIdentity(staffActor, rc.getId(), UUID.randomUUID(), "TEST99999",
            session.reverificationStartedAt()).attendance().verifiedAt()).isNotNull();
        assertThat(ReturnAttendancePolicy.canInspect(rc, Instant.now())).isTrue();
        rc.setReturnReverificationStartedAt(Instant.now().minusSeconds(1900));

        Instant appointment = Instant.now().plusSeconds(3600);
        var decision = service.reviewReturnArrival(managerActor, rc.getId(), true, "Khách đổi lịch", staff.getId(), appointment).attendance();
        assertThat(decision.handoverAppointmentAt()).isEqualTo(appointment);
        assertThat(decision.handoverDueAt()).isEqualTo(schedule.afterWorkingHours(appointment, 4));
        assertThat(ReturnAttendancePolicy.canInspect(rc, appointment.minusNanos(1))).isFalse();
        assertThat(ReturnAttendancePolicy.canInspect(rc, appointment)).isTrue();
        assertThat(ReturnAttendancePolicy.canInspect(rc, decision.handoverDueAt())).isFalse();
        assertThatThrownBy(() -> service.performInspection(staffActor, rc.getId(), setupInspection(true)))
            .hasMessageContaining("Chưa đến lịch bàn giao");
        verify(returnFinance, org.mockito.Mockito.never()).capture(any());
    }

    @Test
    void timelyArrivalCanBeVerifiedAfterRemovalDeadlineWithoutChangingDeadline() {
        var rc = setupReturn(ReturnCaseStatus.scheduled);
        rc.setReturnType(ReturnType.INVENTORY_MISMATCH);
        Instant deadline = Instant.now().minusSeconds(60);
        rental.setInventoryRemovalDeadlineAt(deadline);
        rental.getReservation().setIdentityDocumentNumber("TEST99999");
        rc.setReturnArrivedAt(deadline.minusSeconds(60));
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));

        assertThat(service.verifyReturnIdentity(staffActor, rc.getId(), UUID.randomUUID(), "TEST99999")
            .attendance().verifiedAt()).isAfter(deadline);
        assertThat(ReturnAttendancePolicy.canInspect(rc, Instant.now())).isTrue();
        Instant expiry = ReturnAttendancePolicy.expiresAt(rc);
        assertThat(ReturnAttendancePolicy.canInspect(rc, expiry)).isFalse();
        assertThat(ReturnAttendancePolicy.protectsFromRecovery(rc)).isTrue();
        assertThat(rental.getInventoryRemovalDeadlineAt()).isEqualTo(deadline);
    }

    @Test
    void ownerConfirmsAndRevokesIdempotentlyWithoutSettlement() {
        var rc = setupReturn(ReturnCaseStatus.scheduled);
        when(returnCaseRepository.findByIdAndCustomer_Id(rc.getId(), customer.getId())).thenReturn(Optional.of(rc));
        var first = service.setCustomerClearance(customerActor, rc.getId(), true, 0);
        assertThat(first.customerClearedAt()).isNotNull();
        assertThat(first.status()).isEqualTo(ReturnCaseStatus.scheduled);
        assertThat(service.setCustomerClearance(customerActor, rc.getId(), true, 0).customerClearedAt()).isEqualTo(first.customerClearedAt());
        assertThat(service.getReturnForCustomer(customerActor, rc.getId()).customerClearedAt()).isEqualTo(first.customerClearedAt());
        assertThat(service.setCustomerClearance(customerActor, rc.getId(), false, 0).customerClearedAt()).isNull();
        assertThat(service.setCustomerClearance(customerActor, rc.getId(), false, 0).clearanceRevision()).isOne();
        verify(notificationService, org.mockito.Mockito.times(2)).createFacilityOperationsNotifications(any(), any(), any(), any(), any());
        verify(returnFinance, org.mockito.Mockito.never()).capture(any());
    }

    @Test
    void anotherCustomerAndPostInspectionUpdatesAreRejected() {
        var rc = setupReturn(ReturnCaseStatus.awaiting_customer_confirmation);
        assertThatThrownBy(() -> service.setCustomerClearance(customerActor, rc.getId(), true, 0))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
        when(returnCaseRepository.findByIdAndCustomer_Id(rc.getId(), customer.getId())).thenReturn(Optional.of(rc));
        assertThatThrownBy(() -> service.setCustomerClearance(customerActor, rc.getId(), false, 0))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void inspectionDependsOnPhysicalCheckRegardlessOfCustomerConfirmation(boolean cleared) {
        var rc = setupReturn(ReturnCaseStatus.scheduled);
        rc.setSetupExitRequested(false);
        rc.setCustomerClearedAt(cleared ? Instant.now() : null);
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        assertThatThrownBy(() -> service.performInspection(staffActor, rc.getId(), setupInspection(false)))
            .hasMessageContaining("xác minh danh tính");
        UUID arrivalPhoto = UUID.fromString(inspectionPhoto(rc).getFirst());
        settlementEntityManager.find(com.storagehub.domain.model.FileAsset.class, arrivalPhoto).setUploadedBy(staff);
        rental.getReservation().setIdentityDocumentNumber("TEST99999");
        assertThatThrownBy(() -> service.verifyReturnIdentity(staffActor, rc.getId(), UUID.randomUUID(), "TEST99999"))
            .hasMessageContaining("Cần ảnh hiện trường");
        assertThat(rc.getReturnArrivedAt()).isNull();
        var verified = service.verifyReturnIdentity(staffActor, rc.getId(), UUID.randomUUID(), "TEST99999", null, arrivalPhoto).attendance();
        assertThat(verified.evidenceId()).isEqualTo(arrivalPhoto);
        assertThat(verified.arrivedAt()).isEqualTo(verified.verifiedAt()).isNotNull();
        assertThatThrownBy(() -> service.performInspection(staffActor, rc.getId(), setupInspection(false)))
            .hasMessageContaining("kho trống");
        assertThatThrownBy(() -> service.performInspection(staffActor, rc.getId(), setupInspection(true)))
            .hasMessageContaining("ảnh hiện trường");
        assertThat(rc.getInspectedAt()).isNull();
        when(userRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(returnCaseRepository.save(rc)).thenReturn(rc);
        var result = service.performInspection(staffActor, rc.getId(), propertyInspection(true, inspectionPhoto(rc)));
        assertThat(result.warehouseEmptyVerified()).isTrue();
        assertThat(result.warehouseEmptyVerifiedBy()).isEqualTo(staff.getId());
        assertThat(result.status()).isEqualTo(ReturnCaseStatus.awaiting_customer_confirmation);
    }

    @Test
    void draftPreservesDeadlineAndRevocationInvalidatesPendingInspection() {
        var rc = setupReturn(ReturnCaseStatus.requested);
        rc.setRecoveryEligibleAt(Instant.now().plusSeconds(86400));
        var deadline = rc.getRecoveryEligibleAt();
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        when(returnCaseRepository.findByIdAndCustomer_Id(rc.getId(), customer.getId())).thenReturn(Optional.of(rc));
        var photos = inspectionPhoto(rc);
        var draft = service.savePropertyCheck(staffActor, rc.getId(),
            new com.storagehub.api.returns.ReturnPropertyCheckRequest(false, 0, "Còn hai thùng", photos));
        assertThat(draft.evidencePhotos()).isEqualTo(photos);
        assertThat(draft.inspectionNotes()).isEqualTo("Còn hai thùng");
        assertThat(draft.recoveryEligibleAt()).isEqualTo(deadline);
        assertThat(draft.status()).isEqualTo(ReturnCaseStatus.requested);
        assertThat(draft.inspectedAt()).isNull();
        verify(notificationService).createNotification(eq(customer.getId()), any(), any(), any(), eq(rc.getId()));
        service.setCustomerClearance(customerActor, rc.getId(), true, draft.clearanceRevision());
        var verified = service.savePropertyCheck(staffActor, rc.getId(),
            new com.storagehub.api.returns.ReturnPropertyCheckRequest(true, draft.clearanceRevision(), "Đã kiểm tra", photos));
        var revoked = service.setCustomerClearance(customerActor, rc.getId(), false, verified.clearanceRevision());
        assertThat(revoked.warehouseEmptyVerified()).isNull();
        assertThat(revoked.warehouseEmptyVerifiedBy()).isNull();
        assertThatThrownBy(() -> service.performInspection(staffActor, rc.getId(), propertyInspection(true, photos), verified.clearanceRevision()))
            .hasMessageContaining("kiểm tra thực tế");
        verify(returnFinance, org.mockito.Mockito.never()).capture(any());
    }

    @Test
    void inspectionHttpRejectsMissingSelectionOccupiedAndIncompleteData() throws Exception {
        var rc = setupReturn(ReturnCaseStatus.scheduled);
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        var actors = org.mockito.Mockito.mock(com.storagehub.security.ActorContext.class);
        when(actors.required()).thenReturn(staffActor);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new com.storagehub.api.returns.StaffReturnController(actors, service))
            .setControllerAdvice(new com.storagehub.common.api.GlobalExceptionHandler()).build();
        String path = "/api/staff/returns/" + rc.getId() + "/inspection";
        var body = objectMapper.valueToTree(setupInspection(false));
        for (boolean omit : List.of(false, true)) {
            if (omit) ((com.fasterxml.jackson.databind.node.ObjectNode) body).remove("unitVacated");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .contentType("application/json").content(body.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType("application/json").content("{\"unitVacated\":true}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        verify(returnFinance, org.mockito.Mockito.never()).capture(any());
    }

    @Test
    void verifiedEarlyReturnRefundsUnusedMonths() {
        var rc = setupReturn(ReturnCaseStatus.requested);
        rc.setSetupExitRequested(false);
        rc.setReturnType(ReturnType.EARLY_RETURN);
        setVerifiedReturnAttendance(rc);
        rc.setRequestedBy(customer);
        rc.setRequestedAt(Instant.parse("2026-01-01T00:00:00Z"));
        rc.setScheduledDate(LocalDate.of(2026, 1, 10));
        rental.setStartDate(LocalDate.of(2026, 1, 1));
        rental.setContractEndDate(LocalDate.of(2026, 3, 31));
        var pricing = new com.storagehub.domain.model.ReservationPricingSnapshot();
        pricing.setRentalMonths(3);
        pricing.setMonthlyPrice(new BigDecimal("2000000"));
        pricing.setNetRentalAmount(new BigDecimal("6000000"));
        pricing.setEarlyTerminationPricingSnapshotJson("[{\"rentalMonths\":3,\"monthlyPrice\":2000000,\"freeSetupDays\":0}]");
        when(pricingSnapshotRepository.findByReservation_Id(rental.getReservation().getId())).thenReturn(Optional.of(pricing));
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        when(userRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(returnCaseRepository.save(rc)).thenReturn(rc);
        var result = service.performInspection(staffActor, rc.getId(), propertyInspection(true, inspectionPhoto(rc)));
        assertThat(result.returnType()).isEqualTo(ReturnType.EARLY_RETURN);
        assertThat(result.activeReturnPolicy().rentalRefundAmount()).isEqualByComparingTo("4000000");
        assertThat(result.netRefundAmount()).isEqualByComparingTo("6000000");
    }

    private void setVerifiedReturnAttendance(ReturnCase record) {
        Instant now = Instant.now();
        record.setReturnArrivedAt(now);
        record.setReturnIdentityVerifiedAt(now);
    }

    private List<String> inspectionPhoto(ReturnCase rc) {
        UUID fileId = UUID.randomUUID();
        var asset = new com.storagehub.domain.model.FileAsset();
        asset.setEntityType("RETURN_CASE");
        asset.setEntityId(rc.getId());
        asset.setContentType("image/jpeg");
        when(settlementEntityManager.find(com.storagehub.domain.model.FileAsset.class, fileId)).thenReturn(asset);
        return List.of(fileId.toString());
    }

    private ReturnInspectionRequest setupInspection(boolean vacated) {
        return propertyInspection(vacated, List.of());
    }

    private ReturnInspectionRequest propertyInspection(boolean vacated, List<String> photos) {
        return new ReturnInspectionRequest(InventoryMatch.match, DamageClassification.no_damage,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            "Đã kiểm tra kho trống", photos, true, true, true, StorageUnitStatus.available, vacated);
    }

    @Test
    void validatesSizeSpecificReturnWindowDatesAndPreservesSnapshotOnConfigChange() {
        var unitType = new com.storagehub.domain.model.UnitType();
        unitType.setCode("XL");
        unit.setUnitType(unitType);
        rental.setInventoryPending(true);
        LocalDate today = LocalDate.now(BusinessTime.ZONE);
        Instant publishedAt = today.atTime(9, 0).atZone(BusinessTime.ZONE).toInstant();
        InventoryTimelinePolicy.initializeRemovalWindow(rental, publishedAt);
        assertThat(rental.getInventoryReturnWindowDays()).isEqualTo(10);

        when(rentalRepository.findByIdForUpdate(rental.getId())).thenReturn(Optional.of(rental));
        when(returnCaseRepository.findByRental_Id(rental.getId())).thenReturn(Collections.emptyList());
        when(userRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> {
            ReturnCase rc = invocation.getArgument(0);
            ReflectionTestUtils.setField(rc, "id", UUID.randomUUID());
            return rc;
        });

        // D is before the window and D+11 is after it; D+10 is the final allowed day for XL.
        assertThatThrownBy(() -> service.createCustomerReturnRequest(customerActor, rental.getId(),
            new CustomerCreateReturnRequest(today, "Trước ngày hiệu lực", ReturnType.INVENTORY_MISMATCH)))
            .isInstanceOf(com.storagehub.common.api.ApiException.class);
        assertThatThrownBy(() -> service.createCustomerReturnRequest(customerActor, rental.getId(),
            new CustomerCreateReturnRequest(today.plusDays(11), "Quá hạn 10 ngày", ReturnType.INVENTORY_MISMATCH)))
            .isInstanceOf(com.storagehub.common.api.ApiException.class);

        // Mutate unitType after publication: active rental snapshot must stay 10 days.
        unitType.setCode("S");
        ReturnCaseResponse created = service.createCustomerReturnRequest(customerActor, rental.getId(),
            new CustomerCreateReturnRequest(today.plusDays(10), "Ngày cuối XL", ReturnType.INVENTORY_MISMATCH));
        assertThat(created.returnWindowDays()).isEqualTo(10);
        assertThat(created.scheduledDate()).isEqualTo(today.plusDays(10));
    }

    @Test
    void inventoryMismatchBillingUsesActualDateWhenEarlyAndCapsAtSizeDeadlineWhenOverdue() {
        LocalDate receiptDate = LocalDate.now(BusinessTime.ZONE).minusDays(15);
        LocalDate noticeDate = receiptDate.plusDays(1); // D
        Instant publishedAt = noticeDate.atTime(10, 0).atZone(BusinessTime.ZONE).toInstant();

        var pricing = new com.storagehub.domain.model.ReservationPricingSnapshot();
        pricing.setMonthlyPrice(new BigDecimal("3000000")); // 100,000 / day
        pricing.setNetRentalAmount(new BigDecimal("9000000"));
        when(userRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(pricingSnapshotRepository.findByReservation_Id(rental.getReservation().getId())).thenReturn(Optional.of(pricing));
        when(maintenanceTaskRepository.findByRental_IdAndTaskTypeAndStatusIn(any(), any(), any())).thenReturn(Collections.emptyList());
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 1) Size XL (10 days), overdue & recovered on D+12 -> usage capped at D+10 (receiptDate..noticeDate+10 = 12 days = 1,200,000).
        var xlType = new com.storagehub.domain.model.UnitType();
        xlType.setCode("XL");
        unit.setUnitType(xlType);
        rental.setStatus(RentalStatus.setup);
        rental.setInventoryPending(true);
        rental.setSetupExitReviewRequired(true);
        rental.setSetupStartedAt(receiptDate.atTime(9, 0).atZone(BusinessTime.ZONE).toInstant());
        rental.setMoveInCompletedAt(rental.getSetupStartedAt().plus(1, java.time.temporal.ChronoUnit.HOURS));
        rental.setInventoryRemovalNotifiedAt(null);
        rental.setInventoryRemovalDeadlineAt(null);
        rental.setInventoryReturnWindowDays(null);
        InventoryTimelinePolicy.initializeRemovalWindow(rental, publishedAt);

        var xlCase = setupReturn(ReturnCaseStatus.recovery_required);
        xlCase.setReturnType(ReturnType.INVENTORY_MISMATCH);
        xlCase.setReturnWindowDays(10);
        xlCase.setScheduledDate(noticeDate.plusDays(1));
        var completedRecoveryTask = new MaintenanceTask();
        completedRecoveryTask.setStatus(com.storagehub.domain.model.MaintenanceTaskStatus.completed);
        completedRecoveryTask.setCompletedAt(publishedAt.plus(12, java.time.temporal.ChronoUnit.DAYS));
        when(maintenanceTaskRepository.findFirstByReturnCase_IdAndRecoveryTypeOrderByCreatedAtDesc(
            any(), eq(com.storagehub.domain.model.MaintenanceRecoveryType.RETURN_NO_SHOW)))
            .thenReturn(Optional.of(completedRecoveryTask));
        var witness = new User();
        ReflectionTestUtils.setField(witness, "id", UUID.randomUUID());
        witness.setFullName("Nhân chứng A");
        witness.setStatus(com.storagehub.domain.model.UserStatus.ACTIVE);
        var staffRole = new com.storagehub.domain.model.Role();
        staffRole.setCode(com.storagehub.domain.model.RoleCode.STAFF);
        witness.setRoles(java.util.Set.of(staffRole));
        when(userRepository.findById(witness.getId())).thenReturn(Optional.of(witness));
        var witnessScope = new com.storagehub.domain.model.UserFacilityScope();
        witnessScope.setScopeLevel(com.storagehub.domain.model.FacilityScopeLevel.OPERATE);
        when(userFacilityScopeRepository.findByUser_IdAndFacility_Id(witness.getId(), facility.getId()))
            .thenReturn(Optional.of(witnessScope));
        java.util.function.Function<ReturnCase, ReturnInspectionRequest> recoveryInspection = rc -> new ReturnInspectionRequest(
            InventoryMatch.excess, DamageClassification.no_damage,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            "Thu hồi hàng quá hạn", inspectionPhoto(rc), true, true, true, StorageUnitStatus.available, true,
            "Khu vực lưu tạm A", false, witness.getId(), null, null
        );
        when(returnCaseRepository.findById(xlCase.getId())).thenReturn(Optional.of(xlCase));
        when(returnFinance.capture(xlCase)).thenReturn(
            new com.storagehub.service.ledger.ReturnFinancialReconciliation.View(
                new BigDecimal("2000000"), BigDecimal.ZERO, BigDecimal.ZERO, 1L, BigDecimal.ZERO));

        ReturnCaseResponse xlResponse = service.performInspection(staffActor, xlCase.getId(), recoveryInspection.apply(xlCase));
        // receiptDate to noticeDate+10 inclusive = 12 calendar days * 100,000 = 1,200,000 (not 900,000 for 7d, not 1,400,000 for 12d).
        assertThat(xlResponse.earlyTerminationPolicy().totalRentalCharge()).isEqualByComparingTo("1200000");

        // 2) Size S (3 days), overdue & recovered on D+5 -> usage capped at D+3 (receiptDate..noticeDate+3 = 5 days = 500,000).
        var smallType = new com.storagehub.domain.model.UnitType();
        smallType.setCode("S");
        unit.setUnitType(smallType);
        rental.setInventoryPending(true);
        rental.setInventoryRemovalNotifiedAt(null);
        rental.setInventoryRemovalDeadlineAt(null);
        rental.setInventoryReturnWindowDays(null);
        InventoryTimelinePolicy.initializeRemovalWindow(rental, publishedAt);

        var smallOverdueCase = setupReturn(ReturnCaseStatus.recovery_required);
        smallOverdueCase.setReturnType(ReturnType.INVENTORY_MISMATCH);
        smallOverdueCase.setReturnWindowDays(3);
        smallOverdueCase.setScheduledDate(noticeDate.plusDays(1));
        when(returnCaseRepository.findById(smallOverdueCase.getId())).thenReturn(Optional.of(smallOverdueCase));
        when(returnFinance.capture(smallOverdueCase)).thenReturn(
            new com.storagehub.service.ledger.ReturnFinancialReconciliation.View(
                new BigDecimal("2000000"), BigDecimal.ZERO, BigDecimal.ZERO, 1L, BigDecimal.ZERO));

        ReturnCaseResponse smallOverdueResponse = service.performInspection(staffActor, smallOverdueCase.getId(), recoveryInspection.apply(smallOverdueCase));
        assertThat(smallOverdueResponse.earlyTerminationPolicy().totalRentalCharge()).isEqualByComparingTo("500000");

        // 3) Size S (3 days), returned early on day 1 (D+1) -> usage ends on D+1 (3 days inclusive = 300,000, not 500,000).
        Instant day1Vacate = noticeDate.plusDays(1).atTime(14, 0).atZone(BusinessTime.ZONE).toInstant();
        var smallEarlyCase = setupReturn(ReturnCaseStatus.scheduled);
        smallEarlyCase.setReturnType(ReturnType.INVENTORY_MISMATCH);
        smallEarlyCase.setReturnWindowDays(3);
        smallEarlyCase.setScheduledDate(noticeDate.plusDays(1));
        smallEarlyCase.setReturnArrivedAt(day1Vacate.minusSeconds(60));
        smallEarlyCase.setReturnIdentityVerifiedAt(day1Vacate.minusSeconds(30));
        when(returnCaseRepository.findById(smallEarlyCase.getId())).thenReturn(Optional.of(smallEarlyCase));
        when(returnFinance.capture(smallEarlyCase)).thenReturn(
            new com.storagehub.service.ledger.ReturnFinancialReconciliation.View(
                new BigDecimal("2000000"), BigDecimal.ZERO, BigDecimal.ZERO, 1L, BigDecimal.ZERO));
        try (var clock = org.mockito.Mockito.mockStatic(Instant.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(day1Vacate);
            ReturnCaseResponse smallEarlyResponse = service.performInspection(staffActor, smallEarlyCase.getId(), propertyInspection(true, inspectionPhoto(smallEarlyCase)));
            assertThat(smallEarlyResponse.earlyTerminationPolicy().totalRentalCharge()).isEqualByComparingTo("300000");
        }
    }
}
