package com.storagehub.service.renewal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.service.ledger.RenewalDepositForfeitures;
import com.storagehub.service.renewal.operations.RenewalOperationState;
import com.storagehub.service.renewal.persistence.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class RenewalReturnPolicyTests {
    EntityManager em=mock(EntityManager.class);
    PaymentRepository payments=mock(PaymentRepository.class);
    RentalRepository rentals=mock(RentalRepository.class);
    Rental rental=new Rental();Renewal renewal=new Renewal();
    RenewalDepositForfeitures forfeitures=mock(RenewalDepositForfeitures.class);
    TypedQuery<Renewal> query=mock(TypedQuery.class);
    RenewalReturnPolicy policy;
    @BeforeEach void setup() throws Exception {
        renewal.setRental(rental);renewal.setStatus(RenewalStatus.accepted);
        var beans=new StaticListableBeanFactory();beans.addBean("forfeitures",forfeitures);
        policy=new RenewalReturnPolicy(em,rentals,payments,new ObjectMapper(),beans.getBeanProvider(RenewalDepositForfeitures.class));
        when(em.createQuery(anyString(),eq(Renewal.class))).thenReturn(query);
        when(query.setParameter(anyString(),any())).thenReturn(query);when(query.getResultList()).thenReturn(List.of(renewal));
        var workflow=mock(RenewalWorkflow.class);var revision=mock(RenewalAcceptedRevision.class);var quote=mock(RenewalQuote.class);
        when(em.find(RenewalWorkflow.class,renewal.getId())).thenReturn(workflow);
        when(workflow.getAcceptedRevision()).thenReturn(revision);when(revision.getQuote()).thenReturn(quote);
        when(quote.getTermsJson()).thenReturn("{\"renewalDepositAmount\":20,\"remainingRentalAmount\":80,\"totalAfterDiscount\":100}");
        when(payments.findAllByRenewal_IdOrderByCreatedAtAsc(renewal.getId())).thenReturn(List.of());
        var state=new RenewalOperationState(renewal);
        state.deposit(UUID.randomUUID(),Instant.now(),Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),"TEST","1");
        when(em.find(RenewalOperationState.class,renewal.getId())).thenReturn(state);
    }
    Payment paid(String amount) {
        var p=new Payment();p.setRenewal(renewal);p.setStatus(PaymentStatus.PAID);p.setAmount(new BigDecimal(amount));
        p.setRenewalLedgerReceiptRef(UUID.randomUUID());p.setRefundedAmount(BigDecimal.ZERO);p.setPaidAt(Instant.now());return p;
    }
    void rows(Payment... rows){when(payments.findAllByRenewal_IdOrderByCreatedAtAsc(renewal.getId())).thenReturn(List.of(rows));}
    @Test void unpaidRenewalBlocksReturnAndMustBeCancelledSeparately() {
        assertThat(policy.read(rental).state()).isEqualTo("UNPAID");assertThat(policy.canCancel(renewal)).isTrue();
        assertThatThrownBy(()->policy.requireAllowed(rental)).hasMessageContaining("hủy yêu cầu trước khi trả kho");
        assertThat(renewal.getStatus()).isEqualTo(RenewalStatus.accepted);
    }
    @Test void depositWarnsAboutNoRefundAndCanBeCancelled() {
        renewal.setStatus(RenewalStatus.appointment_scheduled);rows(paid("20"));
        var view=policy.read(rental);assertThat(view.allowed()).isFalse();assertThat(view.state()).isEqualTo("DEPOSIT_PAID");
        assertThat(view.message()).contains("không được hoàn lại");assertThat(policy.canCancel(renewal)).isTrue();
    }
    @Test void fullyPaidInTwoReceiptsCanBeCancelledWithAllMoneyNonrefundable() {
        rows(paid("20"),paid("80"));assertThat(policy.read(rental).state()).isEqualTo("FULLY_PAID");
        assertThat(policy.canCancel(renewal)).isTrue();
        assertThat(policy.read(rental).message()).contains("toàn bộ tiền gia hạn đã thanh toán không được hoàn lại");
    }
    @Test void fullyPaidInOneReceiptCanBeCancelledButCompletedRenewalCannot() {
        rows(paid("100"));assertThat(policy.assess(renewal).state()).isEqualTo("FULLY_PAID");
        renewal.setStatus(RenewalStatus.completed);assertThat(policy.canCancel(renewal)).isFalse();
    }
    @Test void fullyPaidButPartiallyRefundedMustStillBeReconciled() {
        var p=paid("100");p.setRefundedAmount(BigDecimal.ONE);rows(p);
        assertThat(policy.assess(renewal).state()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(policy.canCancel(renewal)).isFalse();
    }
    @Test void pendingAttemptMustNotBeTreatedAsUnpaidOrSafeDepositCancellation() {
        var pending=new Payment();pending.setStatus(PaymentStatus.PROCESSING);rows(paid("20"),pending);
        assertThat(policy.read(rental).state()).isEqualTo("RECONCILIATION_REQUIRED");assertThat(policy.canCancel(renewal)).isFalse();
    }
    @Test void partialRefundCannotBeForfeited() {
        var p=paid("20");p.setRefundedAmount(BigDecimal.ONE);rows(p);
        assertThat(policy.read(rental).state()).isEqualTo("RECONCILIATION_REQUIRED");
    }
    @Test void unpreparedAllocatedPaymentRequiresReconciliation() {
        rows(paid("20"));when(em.find(RenewalOperationState.class,renewal.getId())).thenReturn(null);
        assertThat(policy.read(rental).state()).isEqualTo("RECONCILIATION_REQUIRED");
    }
    @Test void cancelledUnpaidRequestUnblocksReturn() {
        renewal.setStatus(RenewalStatus.cancelled);assertThat(policy.read(rental).allowed()).isTrue();
        assertThat(policy.canCancel(renewal)).isFalse();
    }
    @Test void cancelledButUnresolvedMoneyStillBlocksReturn() {
        renewal.setStatus(RenewalStatus.cancelled);rows(paid("100"));assertThat(policy.read(rental).allowed()).isFalse();
    }
    @Test void forfeitedDepositUnblocksReturnAfterCancellation() {
        var p=paid("20");rows(p);renewal.setStatus(RenewalStatus.cancelled);
        when(forfeitures.containsReceipt(p.getRenewalLedgerReceiptRef())).thenReturn(true);
        assertThat(policy.read(rental).allowed()).isTrue();
    }
    @Test void everyPaidReceiptMustBeForfeitedBeforeReturnUnlocks() {
        var deposit=paid("20");var balance=paid("80");rows(deposit,balance);renewal.setStatus(RenewalStatus.cancelled);
        when(forfeitures.containsReceipt(deposit.getRenewalLedgerReceiptRef())).thenReturn(true);
        assertThat(policy.read(rental).allowed()).isFalse();
        when(forfeitures.containsReceipt(balance.getRenewalLedgerReceiptRef())).thenReturn(true);
        assertThat(policy.read(rental).allowed()).isTrue();
    }
    @Test void completedRenewalUsesTheCurrentRentalWithoutChangingItsDates() {
        rental.setContractEndDate(java.time.LocalDate.of(2027,4,1));when(query.getResultList()).thenReturn(List.of());
        assertThat(policy.read(rental).allowed()).isTrue();assertThat(rental.getContractEndDate()).isEqualTo("2027-04-01");
    }
}
