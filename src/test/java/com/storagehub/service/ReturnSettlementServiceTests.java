package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.ReturnCase;
import com.storagehub.domain.model.ReturnCaseStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.ReturnCaseRepository;
import com.storagehub.domain.repo.StorageUnitRepository;
import com.storagehub.domain.repo.UserRepository;
import com.storagehub.security.ActorPrincipal;
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
        quote.setSetupEarlyExitFee(new BigDecimal("2000000"));
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
        ReflectionTestUtils.setField(service, "settlementEntityManager", settlementEntityManager);
        org.mockito.Mockito.lenient().when(rentalRepository.findByIdForUpdate(rental.getId())).thenReturn(Optional.of(rental));
        org.mockito.Mockito.lenient().when(returnFinance.deposit(rental)).thenReturn(new BigDecimal("2000000.00"));
        org.mockito.Mockito.lenient().when(returnFinance.capture(any(ReturnCase.class))).thenReturn(
            new com.storagehub.service.ledger.ReturnFinancialReconciliation.View(new BigDecimal("2000000.00"), BigDecimal.ZERO, BigDecimal.ZERO, 1L, BigDecimal.ZERO));
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
        record.setScheduledDate(LocalDate.now().plusDays(1));
        when(returnCaseRepository.findOwnedForUpdate(record.getId(), customer.getId())).thenReturn(Optional.of(record));
        when(returnCaseRepository.saveAndFlush(record)).thenReturn(record);
        var result = service.updateCustomerReturnRequest(customerActor, record.getId(),
            new CustomerCreateReturnRequest(LocalDate.now().plusDays(3), "Chuyển địa điểm"));
        assertThat(result.scheduledDate()).isEqualTo(LocalDate.now().plusDays(3));
        assertThat(record.getCustomerNotes()).isEqualTo("Chuyển địa điểm");
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
        ReflectionTestUtils.setField(returnCase, "id", UUID.randomUUID());
        returnCase.setRental(rental);
        returnCase.setFacility(facility);
        returnCase.setStorageUnit(unit);
        returnCase.setCustomer(customer);
        returnCase.setRequestedBy(customer);
        returnCase.setStatus(ReturnCaseStatus.requested);
        returnCase.setDepositAmount(new BigDecimal("2000000.00"));
        returnCase.setScheduledDate(LocalDate.now());

        ReturnInspectionRequest request = new ReturnInspectionRequest(
            InventoryMatch.match,
            DamageClassification.minor_damage,
            new BigDecimal("300000.00"), // damageFee
            new BigDecimal("100000.00"), // cleaningFee
            BigDecimal.ZERO, // lostItemFee
            BigDecimal.ZERO, // overdueFee
            BigDecimal.ZERO, // outstandingFee
            "Mặt sàn xước nhẹ, cần vệ sinh bụi",
            List.of("photo1.jpg"),
            true, true, true,
            StorageUnitStatus.available
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
            List.of("damage.jpg"),
            true, true, true,
            StorageUnitStatus.maintenance
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

    @Test
    void managerReviewDispute_recalculatesAndResolves() {
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
        assertThat(response.netRefundAmount()).isEqualByComparingTo(new BigDecimal("1900000.00"));
        assertThat(response.status()).isEqualTo(ReturnCaseStatus.refund_pending);
        assertThat(response.reviewedByName()).isEqualTo("Le Van Manager");
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
    @org.junit.jupiter.params.provider.CsvSource({"300000,2300000,5700000,0", "8000000,10000000,0,2000000"})
    void setupRefundIncludesVerifiedRentAndOneMonthFeeWithoutCountingPrepaymentAgain(
        String damage, String deductions, String refund, String due) {
        var rc = setupReturn(ReturnCaseStatus.requested);
        rental.setSetupStartedAt(Instant.now().minusSeconds(3600));
        rental.setSetupEndsAt(Instant.now().plusSeconds(86400));
        var pricing = new com.storagehub.domain.model.ReservationPricingSnapshot();
        pricing.setMonthlyPrice(new BigDecimal("2000000"));
        pricing.setNetRentalAmount(new BigDecimal("6000000"));
        pricing.setSetupEarlyExitPolicyVersion(SetupEarlyExitPolicy.VERSION);
        pricing.setSetupEarlyExitFee(new BigDecimal("2000000"));
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
        asset.setEntityType("RETURN_CASE"); asset.setEntityId(rc.getId());
        asset.setStatus(com.storagehub.domain.model.FileAssetStatus.ACTIVE);
        when(settlementEntityManager.find(com.storagehub.domain.model.FileAsset.class, fileId)).thenReturn(asset);
        var inspection = new ReturnInspectionRequest(InventoryMatch.match, DamageClassification.minor_damage,
            new BigDecimal(damage), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            "Vết xước khi trả kho, có ảnh đối chiếu", List.of(fileId.toString()), true, true, true, StorageUnitStatus.available, true);
        var response = service.performInspection(staffActor, rc.getId(), inspection);
        assertThat(response.rentalRefundCredit()).isEqualByComparingTo("6000000");
        assertThat(response.earlyTerminationFee()).isEqualByComparingTo("2000000");
        assertThat(response.totalDeductions()).isEqualByComparingTo(deductions);
        assertThat(response.netRefundAmount()).isEqualByComparingTo(refund);
        assertThat(response.amountDueFromCustomer()).isEqualByComparingTo(due);
        assertThat(response.setupReturnedAt().getNano() % 1000).isZero();
    }

    @Test
    void setupDisputeReviewDeductsEarlyExitFeeFromCombinedVerifiedFunds() {
        var rc = setupReturn(ReturnCaseStatus.disputed);
        rc.setSetupEarlyExit(true);
        rc.setDamageFee(new BigDecimal("500000"));
        rc.setCleaningFee(BigDecimal.ZERO);
        rc.setLostItemFee(BigDecimal.ZERO);
        rc.setEarlyTerminationFee(new BigDecimal("2000000"));
        rc.setRentalRefundCredit(new BigDecimal("6000000"));
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        when(userRepository.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(returnCaseRepository.save(rc)).thenReturn(rc);
        var response = service.managerReviewDispute(managerActor, rc.getId(),
            new ManagerReviewDisputeRequest("Giảm phí theo bằng chứng kiểm kê", new BigDecimal("300000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, StorageUnitStatus.available));
        assertThat(response.totalDeductions()).isEqualByComparingTo("2300000");
        assertThat(response.netRefundAmount()).isEqualByComparingTo("5700000");
        assertThat(response.amountDueFromCustomer()).isZero();
        assertThat(response.status()).isEqualTo(ReturnCaseStatus.refund_pending);
    }

    @Test
    void financeFailureCannotCompleteRefundOrReleaseRental() {
        var rc = setupReturn(ReturnCaseStatus.refund_pending);
        rc.setNetRefundAmount(new BigDecimal("5700000"));
        var file = UUID.randomUUID();
        when(returnCaseRepository.findById(rc.getId())).thenReturn(Optional.of(rc));
        org.mockito.Mockito.doThrow(com.storagehub.common.api.ApiExceptions.conflict("Receipt proof unavailable"))
            .when(returnFinance).receipt(staffActor, rc, "CASH", "REFUND-FAIL", file);
        assertThatThrownBy(() -> service.staffCompleteRefund(staffActor, rc.getId(),
            new StaffCompleteRefundRequest("REFUND-FAIL", "Refund", file, "CASH", true, new BigDecimal("5700000"))))
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

    private ReturnInspectionRequest setupInspection(boolean vacated) {
        return new ReturnInspectionRequest(InventoryMatch.match, DamageClassification.no_damage,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            "Đã kiểm tra kho trống", List.of(), true, true, true, StorageUnitStatus.available, vacated);
    }
}
