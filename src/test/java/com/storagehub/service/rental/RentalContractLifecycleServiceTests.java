package com.storagehub.service.rental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.NotificationService;
import com.storagehub.service.rental.RentalContractLifecycleService.LifecycleExecutionSummary;
import com.storagehub.service.rental.RentalContractLifecycleService.PostExpirationEligibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RentalContractLifecycleServiceTests {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate FIXED_TODAY = LocalDate.of(2026, 10, 15);
    private static final Instant FIXED_NOW = FIXED_TODAY.atTime(12, 0).atZone(ZONE).toInstant();

    @Mock private RentalRepository rentalRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationService notificationService;
    @Mock private UserRepository userRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private EntityManager em;
    @Mock private ObjectProvider<Clock> clocks;
    @Mock private TypedQuery<Payment> paymentQuery;

    private RentalContractLifecycleService service;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_NOW, ZONE);
        lenient().when(clocks.getIfAvailable()).thenReturn(fixedClock);

        service = new RentalContractLifecycleService(
            rentalRepository,
            contractRepository,
            notificationRepository,
            notificationService,
            userRepository,
            paymentRepository,
            auditLogService,
            em,
            clocks
        );
    }

    private User createCustomer(String name, String email) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setFullName(name);
        user.setEmail(email);
        Role role = new Role();
        role.setCode(RoleCode.CUSTOMER);
        user.setRoles(Set.of(role));
        return user;
    }

    private User createManager(String name, String email) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setFullName(name);
        user.setEmail(email);
        Role role = new Role();
        role.setCode(RoleCode.MANAGER);
        user.setRoles(Set.of(role));
        return user;
    }

    private Facility createFacility(String name) {
        Facility facility = new Facility();
        ReflectionTestUtils.setField(facility, "id", UUID.randomUUID());
        facility.setName(name);
        return facility;
    }

    private StorageUnit createStorageUnit(Facility facility, String code, StorageUnitStatus status) {
        StorageUnit unit = new StorageUnit();
        ReflectionTestUtils.setField(unit, "id", UUID.randomUUID());
        unit.setFacility(facility);
        unit.setCode(code);
        unit.setStatus(status);
        return unit;
    }

    private Rental createRental(User customer, Facility facility, StorageUnit unit, LocalDate startDate, LocalDate endDate) {
        Rental rental = new Rental();
        ReflectionTestUtils.setField(rental, "id", UUID.randomUUID());
        rental.setCustomer(customer);
        rental.setFacility(facility);
        rental.setStorageUnit(unit);
        rental.setStartDate(startDate);
        rental.setContractEndDate(endDate);
        rental.setStatus(RentalStatus.active);
        rental.setMonthlyPrice(new BigDecimal("5000000"));
        return rental;
    }

    private Contract createContract(Rental rental, ContractStatus status, LocalDate expiresAtDate) {
        Contract contract = new Contract();
        ReflectionTestUtils.setField(contract, "id", UUID.randomUUID());
        contract.setRental(rental);
        contract.setStatus(status);
        contract.setContractNumber("HD-" + rental.getId().toString().substring(0, 8));
        contract.setSignedAt(FIXED_NOW);
        if (expiresAtDate != null) {
            contract.setExpiresAt(expiresAtDate.atStartOfDay(ZONE).toInstant());
        }
        return contract;
    }

    @Test
    @DisplayName("Pillar 3: Pre-expiration reminders sent at D-14 and D-3 with deduplication")
    void testPreExpirationReminders_DMinus14AndDMinus3() {
        User customer = createCustomer("Nguyen Van A", "a@example.com");
        Facility facility = createFacility("Kho Tan Binh");
        StorageUnit unit1 = createStorageUnit(facility, "TB-101", StorageUnitStatus.occupied);
        StorageUnit unit2 = createStorageUnit(facility, "TB-102", StorageUnitStatus.occupied);

        // Rental 1: expires in 14 days (2026-10-29) -> triggers D-14 reminder
        Rental rental14 = createRental(customer, facility, unit1, LocalDate.of(2026, 7, 29), FIXED_TODAY.plusDays(14));
        // Rental 2: expires in 3 days (2026-10-18) -> triggers D-3 reminder
        Rental rental3 = createRental(customer, facility, unit2, LocalDate.of(2026, 7, 18), FIXED_TODAY.plusDays(3));

        when(rentalRepository.findByStatus(RentalStatus.active)).thenReturn(List.of(rental14, rental3));
        when(notificationRepository.existsMilestone(any(), any(), any())).thenReturn(false);

        LifecycleExecutionSummary summary = service.processContractExpirations();

        assertThat(summary.expirationsEvaluated()).isEqualTo(2);
        assertThat(summary.preExpirationRemindersSent()).isEqualTo(2);
        assertThat(summary.contractsMarkedExpired()).isZero();

        // Verify D-14 reminder sent to customer
        verify(notificationService).createNotification(
            eq(customer.getId()),
            eq(NotificationType.SYSTEM),
            contains("(còn 14 ngày)"),
            anyString(),
            eq(rental14.getId())
        );

        // Verify D-3 reminder sent to customer
        verify(notificationService).createNotification(
            eq(customer.getId()),
            eq(NotificationType.SYSTEM),
            contains("(còn 3 ngày)"),
            anyString(),
            eq(rental3.getId())
        );

        // Deduplication test: when existsMilestone returns true, no notification sent
        reset(notificationService);
        when(notificationRepository.existsMilestone(any(), any(), any())).thenReturn(true);

        LifecycleExecutionSummary summaryDeduplicated = service.processContractExpirations();
        assertThat(summaryDeduplicated.preExpirationRemindersSent()).isZero();
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("Pillar 3: Contract Expiration marks status EXPIRED and triggers handover alert when unreturned")
    void testContractExpiration_AndHandoverAlertWhenUnreturned() {
        User customer = createCustomer("Tran Van B", "b@example.com");
        Facility facility = createFacility("Kho Quan 1");
        StorageUnit unit = createStorageUnit(facility, "Q1-201", StorageUnitStatus.occupied);

        // Rental expired 1 day ago (2026-10-14)
        LocalDate expiredDate = FIXED_TODAY.minusDays(1);
        Rental rental = createRental(customer, facility, unit, LocalDate.of(2026, 7, 14), expiredDate);
        rental.setActualReturnedAt(null); // Warehouse unit has NOT been handed over yet

        Contract contract = createContract(rental, ContractStatus.SIGNED, expiredDate);

        when(rentalRepository.findByStatus(RentalStatus.active)).thenReturn(List.of(rental));
        when(contractRepository.findAllByRentalIdOrderByCreatedAtDesc(rental.getId())).thenReturn(List.of(contract));
        when(notificationRepository.existsMilestone(any(), any(), any())).thenReturn(false);

        User manager = createManager("Manager 1", "mgr@example.com");
        when(userRepository.findByFacilityScope(eq(facility.getId()), any(Collection.class)))
            .thenReturn(List.of(manager));

        LifecycleExecutionSummary summary = service.processContractExpirations();

        assertThat(summary.contractsMarkedExpired()).isEqualTo(1);
        assertThat(summary.handoverAlertsCreated()).isEqualTo(1);
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.EXPIRED);

        // Customer notified that contract expired
        verify(notificationService).createNotification(
            eq(customer.getId()),
            eq(NotificationType.SYSTEM),
            contains("Hợp đồng thuê kho đã hết hạn"),
            anyString(),
            eq(rental.getId())
        );

        // Staff/Manager notified that warehouse unit is not yet handed over
        verify(notificationService).createNotification(
            eq(manager.getId()),
            eq(NotificationType.SYSTEM),
            contains("CẢNH BÁO BÀN GIAO: Hợp đồng hết hạn nhưng kho chưa được bàn giao"),
            contains("chưa được bàn giao"),
            eq(rental.getId())
        );
    }

    @Test
    @DisplayName("Pillar 4: Late payment milestones D-3, D+1, D+3 grace period and D+7 manager escalation")
    void testLatePaymentMilestones() {
        User customer = createCustomer("Le Van C", "c@example.com");
        Facility facility = createFacility("Kho Binh Thanh");

        // D-3: payment deadline is 3 days in future (diff == -3)
        Payment payDMinus3 = new Payment();
        ReflectionTestUtils.setField(payDMinus3, "id", UUID.randomUUID());
        payDMinus3.setInitiatedBy(customer);
        payDMinus3.setStatus(PaymentStatus.PENDING);
        payDMinus3.setAmount(new BigDecimal("3000000"));
        payDMinus3.setCurrency("VND");
        payDMinus3.setPaymentDeadline(FIXED_TODAY.plusDays(3).atTime(17, 0).atZone(ZONE).toInstant());

        // D+1: payment deadline was 1 day ago (diff == 1)
        Payment payDPlus1 = new Payment();
        ReflectionTestUtils.setField(payDPlus1, "id", UUID.randomUUID());
        payDPlus1.setInitiatedBy(customer);
        payDPlus1.setStatus(PaymentStatus.PENDING);
        payDPlus1.setAmount(new BigDecimal("3000000"));
        payDPlus1.setCurrency("VND");
        payDPlus1.setPaymentDeadline(FIXED_TODAY.minusDays(1).atTime(17, 0).atZone(ZONE).toInstant());

        Reservation reservation = new Reservation();
        reservation.setFacility(facility);

        // D+3: payment deadline was 3 days ago (diff == 3) -> 3-day grace period ends, watch list alert
        Payment payDPlus3 = new Payment();
        ReflectionTestUtils.setField(payDPlus3, "id", UUID.randomUUID());
        payDPlus3.setInitiatedBy(customer);
        payDPlus3.setReservation(reservation);
        payDPlus3.setStatus(PaymentStatus.PENDING);
        payDPlus3.setAmount(new BigDecimal("3000000"));
        payDPlus3.setCurrency("VND");
        payDPlus3.setPaymentDeadline(FIXED_TODAY.minusDays(3).atTime(17, 0).atZone(ZONE).toInstant());

        // D+7: payment deadline was 7 days ago (diff == 7) -> escalation to manager
        Payment payDPlus7 = new Payment();
        ReflectionTestUtils.setField(payDPlus7, "id", UUID.randomUUID());
        payDPlus7.setInitiatedBy(customer);
        payDPlus7.setReservation(reservation);
        payDPlus7.setStatus(PaymentStatus.PENDING);
        payDPlus7.setAmount(new BigDecimal("3000000"));
        payDPlus7.setCurrency("VND");
        payDPlus7.setPaymentDeadline(FIXED_TODAY.minusDays(7).atTime(17, 0).atZone(ZONE).toInstant());

        when(em.createQuery(anyString(), eq(Payment.class))).thenReturn(paymentQuery);
        when(paymentQuery.getResultList()).thenReturn(List.of(payDMinus3, payDPlus1, payDPlus3, payDPlus7));
        when(notificationRepository.existsMilestone(any(), any(), any())).thenReturn(false);

        User manager = createManager("Manager 2", "mgr2@example.com");
        when(userRepository.findByFacilityScope(any(), any(Collection.class))).thenReturn(List.of(manager));

        LifecycleExecutionSummary summary = service.processLatePayments();

        assertThat(summary.latePaymentRemindersDMinus3()).isEqualTo(1);
        assertThat(summary.overdueNoticesDPlus1()).isEqualTo(1);
        assertThat(summary.gracePeriodEndedWatchListDPlus3()).isEqualTo(1);
        assertThat(summary.managerEscalationsDPlus7()).isEqualTo(1);

        // Verify D-3 notice
        verify(notificationService).createNotification(
            eq(customer.getId()), eq(NotificationType.PAYMENT), contains("(D-3)"), anyString(), eq(payDMinus3.getId())
        );

        // Verify D+1 notice
        verify(notificationService).createNotification(
            eq(customer.getId()), eq(NotificationType.OVERDUE), contains("(D+1)"), anyString(), eq(payDPlus1.getId())
        );

        // Verify D+3 grace period end alert
        verify(notificationService).createNotification(
            eq(manager.getId()), eq(NotificationType.OVERDUE), contains("(Hết ân hạn)"), anyString(), eq(payDPlus3.getId())
        );

        // Verify D+7 notice to customer and escalation alert to manager
        verify(notificationService).createNotification(
            eq(customer.getId()), eq(NotificationType.OVERDUE), contains("Cảnh báo quá hạn kéo dài (D+7)"), anyString(), eq(payDPlus7.getId())
        );
        verify(notificationService).createNotification(
            eq(manager.getId()), eq(NotificationType.OVERDUE), contains("CẦN MANAGER XEM XÉT (D+7)"), anyString(), eq(payDPlus7.getId())
        );
    }

    @Test
    @DisplayName("Pillar 2: Post-expiration renewal eligibility verification")
    void testPostExpirationRenewalEligibility() {
        User customer = createCustomer("Pham Thi D", "d@example.com");
        Facility facility = createFacility("Kho Thu Duc");
        StorageUnit unit = createStorageUnit(facility, "TD-301", StorageUnitStatus.occupied);

        LocalDate expiredDate = FIXED_TODAY.minusDays(5);
        Rental rental = createRental(customer, facility, unit, LocalDate.of(2026, 7, 10), expiredDate);

        // Case 1: Active rental with unit still occupied -> ELIGIBLE
        Contract contract = createContract(rental, ContractStatus.EXPIRED, expiredDate);
        when(rentalRepository.findById(rental.getId())).thenReturn(Optional.of(rental));
        when(contractRepository.findAllByRentalIdOrderByCreatedAtDesc(rental.getId())).thenReturn(List.of(contract));

        PostExpirationEligibility eligibility = service.checkPostExpirationRenewalEligibility(rental.getId());
        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.storageUnitCode()).isEqualTo("TD-301");

        // Case 2: Contract is TERMINATED -> INELIGIBLE
        contract.setStatus(ContractStatus.TERMINATED);
        PostExpirationEligibility termEligibility = service.checkPostExpirationRenewalEligibility(rental.getId());
        assertThat(termEligibility.eligible()).isFalse();
        assertThat(termEligibility.reason()).contains("chấm dứt trước hạn");

        // Case 3: Rental completed/returned -> INELIGIBLE
        contract.setStatus(ContractStatus.EXPIRED);
        rental.setStatus(RentalStatus.completed);
        rental.setActualReturnedAt(Instant.now());
        PostExpirationEligibility compEligibility = service.checkPostExpirationRenewalEligibility(rental.getId());
        assertThat(compEligibility.eligible()).isFalse();
        assertThat(compEligibility.reason()).contains("đã hoàn tất bàn giao");
    }

    @Test
    @DisplayName("Pillar 3: Contract early termination records audit log and prevents double termination")
    void testContractTermination() {
        User customer = createCustomer("Hoang Van E", "e@example.com");
        Facility facility = createFacility("Kho Phu Nhuan");
        StorageUnit unit = createStorageUnit(facility, "PN-01", StorageUnitStatus.occupied);
        Rental rental = createRental(customer, facility, unit, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 11, 1));
        Contract contract = createContract(rental, ContractStatus.SIGNED, LocalDate.of(2026, 11, 1));

        ActorPrincipal managerActor = new ActorPrincipal(
            UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.MANAGER), Set.of(), Map.of()
        );

        when(contractRepository.findById(contract.getId())).thenReturn(Optional.of(contract));
        when(contractRepository.saveAndFlush(any(Contract.class))).thenAnswer(i -> i.getArgument(0));

        Contract terminated = service.terminateContract(managerActor, contract.getId(), "Khách dọn đi trước hạn thỏa thuận");

        assertThat(terminated.getStatus()).isEqualTo(ContractStatus.TERMINATED);
        verify(auditLogService).recordMutation(
            eq("CONTRACT_TERMINATED"),
            eq("Contract"),
            eq(contract.getId()),
            eq(facility.getId()),
            any(),
            any()
        );

        // Attempting to terminate again throws conflict
        assertThatThrownBy(() -> service.terminateContract(managerActor, contract.getId(), "Lại chấm dứt"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("already terminated");
    }
}
