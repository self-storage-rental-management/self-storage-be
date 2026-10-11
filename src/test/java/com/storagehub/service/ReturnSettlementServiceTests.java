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
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.RentalStatus;
import com.storagehub.domain.model.Reservation;
import com.storagehub.domain.model.ReservationPricingSnapshot;
import com.storagehub.domain.model.ReturnCase;
import com.storagehub.domain.model.ReturnCaseStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.domain.model.User;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.domain.repo.ReservationPricingSnapshotRepository;
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
    @Mock ReservationPricingSnapshotRepository pricingSnapshotRepository;
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
            pricingSnapshotRepository,
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
        assertThat(response.depositAmount()).isNull();
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.return_requested);
    }

    @Test
    void createReturnUsesImmutableSecurityDepositContractAmount() {
        var reservation = new Reservation();
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        rental.setReservation(reservation);
        reservation.setCustomer(customer);reservation.setFacility(facility);
        var snapshot = new ReservationPricingSnapshot();
        ReflectionTestUtils.setField(snapshot,"id",UUID.randomUUID());snapshot.setReservation(reservation);
        snapshot.setPricingPackageCode("TEST_1M");snapshot.setPricingPolicyVersion("TEST-v1");snapshot.setRentalMonths(1);
        snapshot.setMonthlyPrice(new BigDecimal("2000000"));snapshot.setGrossRentalAmount(new BigDecimal("2000000"));
        snapshot.setDiscountRate(BigDecimal.ZERO);snapshot.setDiscountAmount(BigDecimal.ZERO);snapshot.setNetRentalAmount(new BigDecimal("2000000"));
        snapshot.setReservationDepositAmount(BigDecimal.ZERO);snapshot.setRemainingRentalAmount(new BigDecimal("2000000"));
        snapshot.setDueAtCheckIn(new BigDecimal("5500000"));snapshot.setTotalInitialObligation(new BigDecimal("5500000"));
        snapshot.setSecurityDepositAmount(new BigDecimal("3500000"));
        when(pricingSnapshotRepository.findByReservation_Id(reservation.getId())).thenReturn(Optional.of(snapshot));
        when(rentalRepository.findByIdAndCustomer_Id(rental.getId(), customer.getId())).thenReturn(Optional.of(rental));
        when(userRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(invocation -> {
            ReturnCase rc = invocation.getArgument(0);
            ReflectionTestUtils.setField(rc, "id", UUID.randomUUID());
            return rc;
        });

        var request = new CustomerCreateReturnRequest(LocalDate.now().plusDays(1), null);
        var response = service.createCustomerReturnRequest(customerActor, rental.getId(), request);
        assertThat(response.depositAmount()).isEqualByComparingTo("3500000");
        assertThat(response.status()).isEqualTo(ReturnCaseStatus.requested);
    }

    @Test
    void performInspection_withUnverifiedDepositDoesNotCalculateRefund() {
        ReturnCase returnCase = pendingCase(ReturnCaseStatus.requested, "2000000.00");
        when(returnCaseRepository.findById(returnCase.getId())).thenReturn(Optional.of(returnCase));
        when(userRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(i->i.getArgument(0));
        var response=service.performInspection(staffActor, returnCase.getId(), inspection());
        assertThat(returnCase.getStatus()).isEqualTo(ReturnCaseStatus.awaiting_customer_confirmation);
        assertThat(returnCase.getInspectedBy()).isEqualTo(staff);
        assertThat(response.netRefundAmount()).isNull();
        assertThat(response.settlementBlocker()).isEqualTo("FIN_06_HELD_DEPOSIT_EVIDENCE_REQUIRED");
        assertThat(response.amountDueFromCustomer()).isNull();
    }

    @Test
    void performInspection_withUnverifiedDepositDoesNotCalculateAmountDue() {
        ReturnCase returnCase = pendingCase(ReturnCaseStatus.requested, "1000000.00");
        when(returnCaseRepository.findById(returnCase.getId())).thenReturn(Optional.of(returnCase));
        when(userRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(i->i.getArgument(0));
        var response=service.performInspection(staffActor, returnCase.getId(), inspection());
        assertThat(response.status()).isEqualTo(ReturnCaseStatus.awaiting_customer_confirmation);
        assertThat(response.totalDeductions()).isEqualByComparingTo("500000");
        assertThat(response.amountDueFromCustomer()).isNull();
    }

    @Test
    void customerConfirmSettlement_acceptedWithUnverifiedDepositIsBlocked() {
        ReturnCase returnCase = pendingCase(ReturnCaseStatus.awaiting_customer_confirmation, "2000000.00");
        when(returnCaseRepository.findByIdAndCustomer_Id(returnCase.getId(), customer.getId()))
            .thenReturn(Optional.of(returnCase));
        var request = new CustomerConfirmSettlementRequest(CustomerDecision.accepted, "Dong y");
        assertThatThrownBy(() -> service.customerConfirmSettlement(customerActor, returnCase.getId(), request))
            .isInstanceOf(ApiException.class).hasMessageContaining("verified paid security deposit");
        assertThat(returnCase.getStatus()).isEqualTo(ReturnCaseStatus.awaiting_customer_confirmation);
        assertThat(returnCase.isCustomerConfirmed()).isFalse();
        assertThat(returnCase.getCustomerDecision()).isNull();
        assertThat(returnCase.getCustomerConfirmedAt()).isNull();
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
    void managerReviewDispute_preservesNonfinancialReviewWithoutCompletingSettlement() {
        ReturnCase returnCase = pendingCase(ReturnCaseStatus.disputed, "2000000.00");
        when(returnCaseRepository.findById(returnCase.getId())).thenReturn(Optional.of(returnCase));
        when(userRepository.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(returnCaseRepository.save(any(ReturnCase.class))).thenAnswer(i->i.getArgument(0));
        var request=new ManagerReviewDisputeRequest("Reviewed deductions, settlement evidence missing",BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,
            BigDecimal.ZERO,BigDecimal.ZERO,StorageUnitStatus.available);
        var response=service.managerReviewDispute(managerActor, returnCase.getId(), request);
        assertThat(returnCase.getStatus()).isEqualTo(ReturnCaseStatus.disputed);
        assertThat(response.reviewedById()).isEqualTo(manager.getId());
        assertThat(response.netRefundAmount()).isNull();
        assertThat(returnCase.getCompletedAt()).isNull();
        verify(storageUnitRepository,never()).save(any());
    }

    @Test
    void staffCompleteRefund_withoutPayoutEvidencePreservesRental() {
        ReturnCase returnCase = pendingCase(ReturnCaseStatus.refund_pending, "2000000.00");
        when(returnCaseRepository.findById(returnCase.getId())).thenReturn(Optional.of(returnCase));
        var request = new StaffCompleteRefundRequest("BANK-REF-998877", "Entered by staff");
        assertThatThrownBy(() -> service.staffCompleteRefund(staffActor, returnCase.getId(), request))
            .isInstanceOf(ApiException.class).hasMessageContaining("verified payout evidence");
        assertThat(returnCase.getStatus()).isEqualTo(ReturnCaseStatus.refund_pending);
        assertThat(returnCase.getSettlementPaymentId()).isNull();
        verify(returnCaseRepository, never()).save(any(ReturnCase.class));
        verify(rentalRepository, never()).save(any(Rental.class));
    }

    @Test
    void customerPaySettlement_requiresVerifiedPayment() {
        ReturnCase returnCase = pendingCase(ReturnCaseStatus.payment_due, "2000000.00");
        when(returnCaseRepository.findByIdAndCustomer_Id(returnCase.getId(), customer.getId()))
            .thenReturn(Optional.of(returnCase));
        var request = new CustomerPaySettlementRequest("VNPAY", "Entered by customer");
        assertThatThrownBy(() -> service.customerPaySettlement(customerActor, returnCase.getId(), request))
            .isInstanceOf(ApiException.class).hasMessageContaining("verified payment transaction");
        assertThat(returnCase.getStatus()).isEqualTo(ReturnCaseStatus.payment_due);
        assertThat(returnCase.getSettlementPaymentId()).isNull();
        verify(returnCaseRepository, never()).save(any(ReturnCase.class));
        verify(rentalRepository, never()).save(any(Rental.class));
    }

    private ReturnCase pendingCase(ReturnCaseStatus status, String deposit) {
        ReturnCase result = new ReturnCase();
        ReflectionTestUtils.setField(result, "id", UUID.randomUUID());
        result.setRental(rental);
        result.setFacility(facility);
        result.setStorageUnit(unit);
        result.setCustomer(customer);
        result.setStatus(status);
        result.setDepositAmount(new BigDecimal(deposit));
        return result;
    }

    private ReturnInspectionRequest inspection() {
        return new ReturnInspectionRequest(InventoryMatch.values()[0],DamageClassification.values()[0],
            new BigDecimal("500000"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,
            "LOCAL TEST inspection",List.of(),true,true,true,StorageUnitStatus.available);
    }

}
