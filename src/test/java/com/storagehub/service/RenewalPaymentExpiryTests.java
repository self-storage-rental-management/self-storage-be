package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.service.renewal.operations.*;
import com.storagehub.service.renewal.persistence.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

/** Race boundary checks: the locked Payment is authoritative, never the scheduler's old candidate. */
class RenewalPaymentExpiryTests {
    PaymentRepository payments=mock(PaymentRepository.class);VnpayService gateway=mock(VnpayService.class);
    EntityManager em=mock(EntityManager.class);RenewalPersistence persistence=mock(RenewalPersistence.class);
    RenewalOperationSources.SafetySource safety=mock(RenewalOperationSources.SafetySource.class);
    RenewalPaymentExpiryService service;Payment payment=new Payment();Renewal renewal=new Renewal();
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(payment,"id",UUID.randomUUID());ReflectionTestUtils.setField(renewal,"id",UUID.randomUUID());
        var rental=new Rental();var facility=new Facility();ReflectionTestUtils.setField(facility,"id",UUID.randomUUID());rental.setFacility(facility);renewal.setRental(rental);renewal.setStatus(RenewalStatus.accepted);
        var customer=new User();ReflectionTestUtils.setField(customer,"id",UUID.randomUUID());rental.setCustomer(customer);
        payment.setRenewal(renewal);payment.setPurpose(PaymentType.RENEWAL);payment.setPaymentDeadline(Instant.now().minusSeconds(30));
        var beans=new StaticListableBeanFactory();beans.addBean("safety",safety);
        service=new RenewalPaymentExpiryService(payments,gateway,em,persistence,new ObjectMapper().findAndRegisterModules(),mock(AuditLogService.class),mock(NotificationService.class),beans.getBeanProvider(RenewalOperationSources.SafetySource.class));
        when(payments.findByStatus(PaymentStatus.PENDING)).thenReturn(List.of(payment));
        when(payments.findByIdForUpdate(payment.getId())).thenReturn(Optional.of(payment));
    }
    @Test void callbackWinningThePaymentLockPreventsResourceRelease() {
        payment.setStatus(PaymentStatus.PAID);
        assertThat(service.reconcileExpiredIntents()).isZero();verifyNoInteractions(gateway,safety,persistence);
    }
    @Test void unresolvedGatewayOutcomeRetainsHoldAndWorkflowSlot() {
        payment.setStatus(PaymentStatus.PENDING);when(gateway.reconcileAtPaymentDeadline(payment)).thenReturn(VnpayService.ReconciliationOutcome.UNKNOWN);
        assertThat(service.reconcileExpiredIntents()).isZero();verifyNoInteractions(safety,persistence);
    }
    @Test void reconciliationFindingFundsRetainsResourcesEvenBeforeAllocation() {
        payment.setStatus(PaymentStatus.PROCESSING);when(gateway.reconcileAtPaymentDeadline(payment)).thenAnswer(c->{payment.setStatus(PaymentStatus.PAID);return VnpayService.ReconciliationOutcome.UNKNOWN;});
        assertThat(service.reconcileExpiredIntents()).isZero();assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);verifyNoInteractions(safety,persistence);
    }
    @Test void onlyAuthoritativeFinalFailureCanExpireAndPhaseIsSetBeforePersistence() {
        payment.setStatus(PaymentStatus.PENDING);when(gateway.reconcileAtPaymentDeadline(payment)).thenAnswer(c->{payment.setStatus(PaymentStatus.FAILED);return VnpayService.ReconciliationOutcome.FINAL_FAILURE;});
        when(em.find(eq(Renewal.class),eq(renewal.getId()),any(jakarta.persistence.LockModeType.class))).thenReturn(renewal);
        var workflow=mock(RenewalWorkflow.class);when(workflow.getPaymentDeadline()).thenReturn(payment.getPaymentDeadline());when(workflow.getExtensionHoldRef()).thenReturn(UUID.randomUUID());
        when(em.find(eq(RenewalWorkflow.class),eq(renewal.getId()),any(jakarta.persistence.LockModeType.class))).thenReturn(workflow);
        when(safety.atomic()).thenReturn(true);
        assertThat(service.reconcileExpiredIntents()).isEqualTo(1);verify(safety).release(renewal,workflow.getExtensionHoldRef());verify(persistence).releaseSlot(workflow);
        verify(em).persist(argThat(value->value instanceof RenewalOperationState state&&state.getPhase()==RenewalOperationState.Phase.PAYMENT_EXPIRED));
        assertThat(renewal.getStatus()).isEqualTo(RenewalStatus.payment_expired);
    }
    @Test void simpleIntentWithoutAHoldExpiresOnlyAfterVerifiedFinalFailure() {
        ReflectionTestUtils.setField(service,"simplePayment",true);
        payment.setStatus(PaymentStatus.PENDING);
        when(gateway.reconcileAtPaymentDeadline(payment)).thenAnswer(c->{payment.setStatus(PaymentStatus.FAILED);return VnpayService.ReconciliationOutcome.FINAL_FAILURE;});
        when(em.find(eq(Renewal.class),eq(renewal.getId()),any(jakarta.persistence.LockModeType.class))).thenReturn(renewal);
        var workflow=mock(RenewalWorkflow.class);when(workflow.getPaymentDeadline()).thenReturn(payment.getPaymentDeadline());
        when(em.find(eq(RenewalWorkflow.class),eq(renewal.getId()),any(jakarta.persistence.LockModeType.class))).thenReturn(workflow);
        assertThat(service.reconcileExpiredIntents()).isEqualTo(1);
        verifyNoInteractions(safety);verify(persistence).releaseSlot(workflow);
    }
}
