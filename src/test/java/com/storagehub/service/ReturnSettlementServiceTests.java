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

        customerActor = new ActorPrincipal(
            customer.getId(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Collections.emptySet(), Collections.emptyMap()
        );
        staffActor = new ActorPrincipal(
            staff.getId(), UUID.randomUUID(), Set.of(RoleCode.STAFF), Set.of("returns:process", "returns:read"),
            Map.of(facility.getId(), com.storagehub.domain.model.FacilityScopeLevel.OPERATE)
        );
        managerActor = new ActorPrincipal(
            manager.getId(), UUID.randomUUID(), Set.of(RoleCode.MANAGER), Set.of("rentals:update", "returns:read"),
            Map.of(facility.getId(), com.storagehub.domain.model.FacilityScopeLevel.MANAGE)
        );
    }

    @Test
    void createCustomerReturnRequest_success() {
        CustomerCreateReturnRequest request = new CustomerCreateReturnRequest(
            LocalDate.now().plusDays(5),
            "Khong con nhu cau su dung"
        );

        when(rentalRepository.findByIdAndCustomer_Id(rental.getId(), customer.getId())).thenReturn(Optional.of(rental));
        when(returnCaseRepository.existsByRental_IdAndStatusNotIn(eq(rental.getId()), any())).thenReturn(false);
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
            "Da chuyen khoan tra khach qua Vietcombank"
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
    }
}
