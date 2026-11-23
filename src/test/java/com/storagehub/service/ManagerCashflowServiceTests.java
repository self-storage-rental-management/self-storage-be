package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ManagerCashflowServiceTests {
    @Test void refundsUsePayoutMonthAndDeduplicateEvents() {
        var events = List.of(event("p", "COLLECTION", 1000, "2026-10-31T16:59:00Z"),
            event("r", "REFUND", 300, "2026-10-31T17:00:00Z"),
            event("r", "REFUND", 300, "2026-10-31T17:00:00Z"));
        var months = ManagerCashflowService.aggregate(events, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-30"));
        assertThat(months.get(0).collected()).isEqualByComparingTo("1000");
        assertThat(months.get(0).refunded()).isZero();
        assertThat(months.get(1).refunded()).isEqualByComparingTo("300");
        assertThat(months.get(1).net()).isEqualByComparingTo("-300");
    }

    @Test void includesFinalDayInVietnamAndExcludesFollowingDay() {
        var months = ManagerCashflowService.aggregate(List.of(event("a", "REFUND", 10, "2026-11-30T16:59:59Z"),
            event("b", "REFUND", 20, "2026-11-30T17:00:00Z")), LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"));
        assertThat(months.getFirst().refunded()).isEqualByComparingTo("10");
    }

    @Test void usesRecordedTransactionsIncludingSandboxAndKeepsPendingOutOfNet() {
        var em = mock(EntityManager.class);
        var scopes = mock(FacilityScopeService.class);
        var service = new ManagerCashflowService(em, mock(AdminAuthorizationService.class), scopes);
        UUID facility = UUID.randomUUID();
        var reservation = reservation("RSV-TEST", "Khách");
        var rental = id(new Rental()); rental.setReservation(reservation);
        var payment = paidPayment(reservation, "1000"); payment.setGatewayProvider("VNPAY_SANDBOX");
        var pending = returnCase(rental, ReturnCaseStatus.refund_pending, "200", null, null);
        var paid = returnCase(rental, ReturnCaseStatus.completed, "300", Instant.now(), "TX1");
        query(em, Payment.class, List.of(payment)); query(em, ReturnCase.class, List.of(pending, paid));
        var request = id(new RefundRequest()); request.setPayment(payment); request.setReturnCase(pending);
        request.setAmount(new BigDecimal("200")); request.setStatus(RefundRequestStatus.APPROVED);
        query(em, RefundRequest.class, List.of(request)); query(em, Refund.class, List.of()); query(em, Rental.class, List.of());

        var report = service.read(null, facility, LocalDate.now(ManagerCashflowService.ZONE).minusDays(1), LocalDate.now(ManagerCashflowService.ZONE));

        assertThat(report.collected()).isEqualByComparingTo("1000");
        assertThat(report.refunded()).isEqualByComparingTo("300");
        assertThat(report.netRetained()).isEqualByComparingTo("700");
        assertThat(report.pendingRefund()).isEqualByComparingTo("200");
        assertThat(report.completeness()).isEqualTo("RECORDED");
        assertThat(report.warnings()).noneMatch(w -> w.toLowerCase().contains("sandbox"));
        verify(scopes).assertCanRead(null, facility);
    }

    @Test void refundRequestAndExecutionWithSameReferenceCountOnce() {
        var em = mock(EntityManager.class);
        var service = new ManagerCashflowService(em, mock(AdminAuthorizationService.class), mock(FacilityScopeService.class));
        var reservation = reservation("RSV-REFUND", "Khách hoàn");
        var payment = paidPayment(reservation, "1000");
        var request = id(new RefundRequest()); request.setPayment(payment); request.setAmount(new BigDecimal("250"));
        request.setStatus(RefundRequestStatus.REFUNDED); request.setProcessedAt(Instant.now()); request.setGatewayRefundId("RF-1");
        var execution = id(new Refund()); execution.setPayment(payment); execution.setAmount(new BigDecimal("250"));
        execution.setStatus(RefundStatus.COMPLETED); execution.setGatewayRefundId("RF-1"); execution.setExecutedAt(Instant.now());
        query(em, Payment.class, List.of(payment)); query(em, ReturnCase.class, List.of());
        query(em, RefundRequest.class, List.of(request)); query(em, Refund.class, List.of(execution)); query(em, Rental.class, List.of());

        var report = service.read(null, UUID.randomUUID(), LocalDate.now(ManagerCashflowService.ZONE).minusDays(1), LocalDate.now(ManagerCashflowService.ZONE));

        assertThat(report.refunded()).isEqualByComparingTo("250");
        assertThat(report.settlements()).hasSize(1);
        assertThat(report.settlements().getFirst().payoutRecorded()).isTrue();
    }

    @Test void rejectsCrossFacilityBeforeReadingAnyPayments() {
        var em = mock(EntityManager.class); var scopes = mock(FacilityScopeService.class);
        UUID facility = UUID.randomUUID(); var actor = mock(ActorPrincipal.class);
        doThrow(new IllegalStateException("Denied")).when(scopes).assertCanRead(actor, facility);
        var service = new ManagerCashflowService(em, mock(AdminAuthorizationService.class), scopes);
        assertThatThrownBy(() -> service.read(actor, facility, null, null)).hasMessage("Denied");
        verifyNoInteractions(em);
    }

    @Test void depositComesFromRecordedBalanceAllocationNotMonthlyPrice() {
        var em = mock(EntityManager.class);
        var service = new ManagerCashflowService(em, mock(AdminAuthorizationService.class), mock(FacilityScopeService.class));
        var reservation = reservation("RSV-DEPOSIT", "Khách cọc");
        var rental = id(new Rental()); rental.setReservation(reservation); rental.setMonthlyPrice(new BigDecimal("999999"));
        var payment = paidPayment(reservation, "1000"); payment.setPurpose(PaymentType.RENTAL_BALANCE);
        var snapshot = new ReservationPricingSnapshot(); snapshot.setSecurityDepositAmount(new BigDecimal("200"));
        snapshot.setRemainingRentalAmount(new BigDecimal("800")); snapshot.setDueAtCheckIn(new BigDecimal("1000"));
        query(em, Payment.class, List.of(payment)); query(em, ReturnCase.class, List.of()); query(em, RefundRequest.class, List.of());
        query(em, Refund.class, List.of()); query(em, Rental.class, List.of(rental)); query(em, ReservationPricingSnapshot.class, List.of(snapshot));

        var report = service.read(null, UUID.randomUUID(), null, null);

        assertThat(report.securityDepositHeld()).isEqualByComparingTo("200");
        assertThat(report.collected()).isEqualByComparingTo("1000");
    }

    private static Reservation reservation(String code, String customerName) {
        var reservation = id(new Reservation()); reservation.setReservationCode(code);
        var customer = id(new User()); customer.setFullName(customerName); reservation.setCustomer(customer);
        return reservation;
    }

    private static Payment paidPayment(Reservation reservation, String amount) {
        var payment = id(new Payment()); payment.setReservation(reservation); payment.setPurpose(PaymentType.RESERVATION_DEPOSIT);
        payment.setStatus(PaymentStatus.PAID); payment.setAmount(new BigDecimal(amount)); payment.setCurrency("VND");
        payment.setPaidAt(Instant.now().minusSeconds(60)); return payment;
    }

    private static ReturnCase returnCase(Rental rental, ReturnCaseStatus status, String refund, Instant paidAt, String reference) {
        var rc = id(new ReturnCase()); rc.setRental(rental); rc.setCustomer(rental.getReservation().getCustomer());
        rc.setReturnType(ReturnType.NORMAL); rc.setStatus(status); rc.setNetRefundAmount(new BigDecimal(refund));
        rc.setSettlementPaidAt(paidAt); rc.setSettlementPaymentId(reference); return rc;
    }

    private static ManagerCashflowService.Event event(String id, String direction, int amount, String at) {
        return new ManagerCashflowService.Event(id, direction, BigDecimal.valueOf(amount), Instant.parse(at), id);
    }

    private static <T extends BaseEntity> T id(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID()); return entity;
    }

    @SuppressWarnings("unchecked")
    private static <T> void query(EntityManager em, Class<T> type, List<T> values) {
        TypedQuery<T> query = mock(TypedQuery.class); when(em.createQuery(anyString(), eq(type))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query); when(query.getResultList()).thenReturn(values);
    }
}
