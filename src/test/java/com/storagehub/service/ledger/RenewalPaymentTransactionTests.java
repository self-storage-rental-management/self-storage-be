package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.RenewalQuoteResponse;
import com.storagehub.config.VnpayProperties;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.*;
import com.storagehub.service.renewal.RenewalSources;
import com.storagehub.service.renewal.RenewalWorkflowService;
import com.storagehub.service.renewal.operations.RenewalOperationSources;
import com.storagehub.service.renewal.persistence.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real H2 JPA/JDBC commits and concurrent workers. No external gateway or shared database. */
@DataJpaTest(properties={"storagehub.integration.ledger.enabled=true","storagehub.integration.renewal-obligations.enabled=true",
    "storagehub.integration.sandbox-reconciliation.enabled=true","spring.jpa.show-sql=false",
    "spring.test.database.replace=NONE","spring.datasource.url=jdbc:h2:mem:renewal-payment-transactions;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "logging.level.com.storagehub.service.ledger.RenewalPaymentReconciler=DEBUG"})
@ActiveProfiles("test") @Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({AuditLogService.class,PaymentProvenance.class,RenewalVnpaySettlementService.class,RenewalPaymentReconciler.class,
    com.storagehub.service.renewal.RenewalStatusProjection.class,com.storagehub.service.renewal.RenewalTransactionReader.class,
    com.storagehub.service.renewal.RenewalReadService.class,
    RenewalPaymentTransactionTests.Sources.class})
class RenewalPaymentTransactionTests {
    @org.springframework.test.context.bean.override.mockito.MockitoBean BusinessRecordCodes displayCodes;
    @Autowired com.storagehub.service.renewal.RenewalReadService renewalReads;
    @Autowired com.storagehub.service.renewal.RenewalStatusProjection statusProjection;

    @Test void getDecoratesCommittedDataButReadsInsideCommandsKeepTheirOwnTransaction() throws Exception {
        UUID paymentId=fixture();
        var owner=tx.execute(s->{var n=payments.findById(paymentId).orElseThrow().getRenewal();
            return new com.storagehub.security.ActorPrincipal(n.getRequestedBy().getId(),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of());});
        UUID renewalId=tx.execute(s->payments.findById(paymentId).orElseThrow().getRenewal().getId());
        var detail=renewalReads.detail(owner,renewalId,false);
        assertThat(detail.statusSummary()).isNotNull();
        assertThat(detail.statusSummary().invoice().status()).isEqualTo("UNPAID");
        tx.executeWithoutResult(s->{
            var within=renewalReads.detail(owner,renewalId,false);
            assertThat(within.statusSummary()).isNull();
            assertThat(s.isRollbackOnly()).isFalse();
        });
    }

    @Test void statusReadDistinguishesCommittedGatewayFundsFromAllocationAndSigning() throws Exception {
        UUID id=fixture();
        var callback=callback(id,Map.of());
        tx.executeWithoutResult(s->gateway.processCallback(callback));
        var before=tx.execute(s->statusProjection.project(payments.findById(id).orElseThrow().getRenewal(),NOW.plusSeconds(3600)));
        assertThat(before.workflowStatus()).isEqualTo("AWAITING_RECONCILIATION");
        assertThat(before.invoice().status()).isEqualTo("UNPAID");
        assertThat(before.transactions().getFirst().status()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(before.transactions().getFirst().receiptAvailable()).isFalse();
        worker.reconcile(id);
        var after=tx.execute(s->statusProjection.project(payments.findById(id).orElseThrow().getRenewal(),NOW.plusSeconds(3600)));
        assertThat(after.invoice().status()).isEqualTo("PAID");
        assertThat(after.workflowStatus()).isEqualTo("AWAITING_RECONCILIATION");
        assertThat(after.transactions().getFirst().receiptAvailable()).isFalse();
    }
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.storagehub.service.NotificationService workflowNotifications;
    static final Instant NOW=Instant.parse("2026-10-11T01:00:00Z");
    @TestConfiguration static class Sources {
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean ActorContext actors(){return mock(ActorContext.class);}
        @Bean LedgerStore ledger(JdbcTemplate jdbc){return new LedgerStore(jdbc);}
        @Bean Clock clock(){return Clock.fixed(NOW.plusSeconds(3600),ZoneOffset.UTC);}
        @Bean RenewalOperationSources.PolicySource policy(){return mock(RenewalOperationSources.PolicySource.class);}
        @Bean RenewalOperationSources.CalendarSource calendar(){return mock(RenewalOperationSources.CalendarSource.class);}
        @Bean RenewalOperationSources.SafetySource safety(){return mock(RenewalOperationSources.SafetySource.class);}
        @Bean RenewalSources.FinancialSource finance(){return mock(RenewalSources.FinancialSource.class);}
        @Bean com.storagehub.service.renewal.integration.RenewalLocalPaymentChecks localPaymentChecks(
            EntityManager em, ObjectProvider<LedgerStore> ledgers,
            ObjectProvider<com.storagehub.service.overdue.OverdueSources.TermSource> terms) {
            return new com.storagehub.service.renewal.integration.RenewalLocalPaymentChecks(em,ledgers,terms);
        }
    }
    @Autowired EntityManager em; @Autowired PaymentRepository payments; @Autowired JdbcTemplate jdbc;
    @Autowired AuditLogService audit; @Autowired ObjectMapper mapper; @Autowired LedgerStore ledger;
    @Autowired PaymentProvenance provenance;
    @Autowired RenewalVnpaySettlementService settlement; @Autowired RenewalPaymentReconciler worker;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Autowired ObjectProvider<RenewalVnpaySettlementService> settlementProvider;
    @Autowired RenewalOperationSources.PolicySource policy; @Autowired RenewalOperationSources.CalendarSource calendar;
    @Autowired RenewalOperationSources.SafetySource safety; @Autowired RenewalSources.FinancialSource finance;
    TransactionTemplate tx; VnpayService gateway; VnpayProperties config;

    @BeforeEach void setup() {
        tx=new TransactionTemplate(manager);reset(policy,calendar,safety,finance);
        if(jdbc.queryForObject("select count(*) from information_schema.tables where table_name='RENTAL_LEDGER_ACCOUNTS'",Long.class)==0)
            new ResourceDatabasePopulator(new ClassPathResource("rental-ledger-test-schema.sql")).execute(jdbc.getDataSource());
        jdbc.execute("create table if not exists rental_ledger_gateway_evidence(receipt_id varchar(36) primary key,payment_id varchar(36) unique,proof_log_id varchar(36) unique,environment varchar(16),transaction_no varchar(128) unique)");
        jdbc.execute("create table if not exists renewal_deposit_forfeitures(renewal_id varchar(36) primary key,rental_id varchar(36),payment_id varchar(36) unique,receipt_id varchar(36) unique,obligation_id varchar(36),amount decimal(19,2),environment varchar(16),forfeited_at timestamp,reason varchar(64))");
        config=new VnpayProperties();config.setEnabled(true);config.setTmnCode("TEST");config.setHashSecret("test-only");
        gateway=new VnpayService(config,payments,mock(PaymentComplaintRepository.class),mock(ReservationRepository.class),
            mock(ReservationPricingSnapshotRepository.class),mock(UserRepository.class),mock(AdminAuthorizationService.class),
            audit,mock(NotificationService.class),mapper,mock(RenewalWorkflowService.class),settlementProvider);
    }

    @Test void callbackCommitsVerifiedMoneyBeforeAnyAllocationAndDelayedArrivalStillAllocates() throws Exception {
        UUID id=fixture();var callback=callback(id,Map.of());
        tx.executeWithoutResult(s->gateway.processCallback(callback));
        assertThat(read(id).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(read(id).getRenewalEvidenceRef()).isNotNull();
        assertThat(receiptCount(id)).isZero();
        worker.reconcile(id);
        assertThat(receiptCount(id)).isEqualTo(1);
        assertThat(read(id).getRenewalAllocatedAt()).isNotNull();
        assertThat(read(id).getRenewalSigningPreparedAt()).isNull();
        assertThat(read(id).getFailureCode()).isEqualTo("RENEWAL_PAYMENT_RECONCILIATION_REQUIRED");
    }
    @Test void allocationRollsBackOnLedgerFailureButVerifiedPaymentAndProofSurvive() throws Exception {
        UUID id=fixture();var callback=callback(id,Map.of());tx.executeWithoutResult(s->gateway.processCallback(callback));
        var broken=mock(RenewalVnpaySettlementService.class);
        when(broken.settle(any(),any())).thenThrow(new IllegalStateException("test-only ledger outage"));
        new RenewalPaymentReconciler(payments,broken,manager).reconcile(id);
        assertThat(read(id).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(read(id).getRenewalEvidenceRef()).isNotNull();assertThat(receiptCount(id)).isZero();
        assertThat(read(id).getRenewalNextRetryAt()).isNotNull();
        worker.reconcile(id);assertThat(receiptCount(id)).isEqualTo(1);
    }
    @Test void activationExceptionCannotRollBackCommittedAllocation() throws Exception {
        UUID id=fixture();var callback=callback(id,Map.of());tx.executeWithoutResult(s->gateway.processCallback(callback));
        var broken=mock(RenewalVnpaySettlementService.class);
        when(broken.settle(any(),any())).thenAnswer(call->settlement.settle(call.getArgument(0),call.getArgument(1)));
        when(broken.prepareSigning(any())).thenThrow(new IllegalStateException("test-only activation failure"));
        new RenewalPaymentReconciler(payments,broken,manager).reconcile(id);
        assertThat(read(id).getStatus()).isEqualTo(PaymentStatus.PAID);assertThat(receiptCount(id)).isEqualTo(1);
        assertThat(read(id).getRenewalAllocatedAt()).isNotNull();assertThat(read(id).getRenewalSigningPreparedAt()).isNull();
    }
    @Test void repeatedAndConflictingCallbacksAndConcurrentWorkersAllocateExactlyOnce() throws Exception {
        UUID id=fixture();var same=callback(id,Map.of());tx.executeWithoutResult(s->gateway.processCallback(same));
        UUID proof=read(id).getRenewalEvidenceRef();
        tx.executeWithoutResult(s->gateway.processCallback(same));
        var conflict=callback(id,Map.of("vnp_TransactionNo","other"));tx.executeWithoutResult(s->gateway.processCallback(conflict));
        assertThat(read(id).getRenewalEvidenceRef()).isEqualTo(proof);
        var pool=Executors.newFixedThreadPool(2);
        try {var a=pool.submit(()->worker.reconcile(id));var b=pool.submit(()->worker.reconcile(id));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);}
        finally {pool.shutdownNow();}
        assertThat(receiptCount(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from rental_ledger_gateway_evidence where payment_id=?",Long.class,id.toString())).isEqualTo(1);
    }
    @Test void afterDeadlineFundsArePreservedWithoutAllocation() throws Exception {
        UUID id=fixture();var late=callback(id,Map.of("vnp_PayDate","20261011100000"));
        tx.executeWithoutResult(s->gateway.processCallback(late));worker.reconcile(id);
        assertThat(read(id).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(read(id).getFailureCode()).isEqualTo("PAYMENT_AFTER_DEADLINE");assertThat(receiptCount(id)).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void paidSelectedAppointmentKeepsSigningWindowEvenAfterAppointmentEnd(boolean expired) throws Exception {
        UUID id=fixture();tx.executeWithoutResult(s->gateway.processCallback(callbackUnchecked(id)));
        Instant start=NOW.plusSeconds(expired?900:2700),end=start.plusSeconds(1800),cutoff=NOW.plusSeconds(172800);
        tx.executeWithoutResult(s->{
            var p=payments.findById(id).orElseThrow();
            var state=new com.storagehub.service.renewal.operations.RenewalOperationState(p.getRenewal());
            state.awaitingPayment(end,cutoff,"selected","1");state.appointment(UUID.randomUUID(),start,end);em.persist(state);
        });
        when(safety.atomic()).thenReturn(true);when(calendar.atomic()).thenReturn(true);
        when(safety.check(any(),any(),any(),any())).thenReturn(cutoff);
        when(calendar.slot(any(),eq(start))).thenReturn(Optional.of(new RenewalOperationSources.Slot("slot",start,end)));
        when(finance.consistentThroughApproval()).thenReturn(true);
        when(finance.read(any())).thenReturn(Optional.of(new RenewalSources.Financial(NOW,List.of(),false)));
        worker.reconcile(id);
        assertThat(read(id).getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(receiptCount(id)).isEqualTo(1);
        assertThat(read(id).getRenewalSigningPreparedAt()).as(read(id).getFailureReason()).isNotNull();
        tx.executeWithoutResult(s->{
            var p=payments.findById(id).orElseThrow();
            var state=em.find(com.storagehub.service.renewal.operations.RenewalOperationState.class,p.getRenewal().getId());
            assertThat(state.getEffectiveSigningDeadline()).isEqualTo(cutoff);
            assertThat(state.getEffectiveSigningDeadline()).isAfter(end);
        });
    }
    private Map<String,String> callbackUnchecked(UUID id) {
        try{return callback(id,Map.of());}catch(Exception error){throw new IllegalStateException(error);}
    }
    @Test void signingCanRetryAfterAllocationWithoutExtendingRentalOrDuplicatingReceipt() throws Exception {
        UUID id=fixture();var callback=callback(id,Map.of());tx.executeWithoutResult(s->gateway.processCallback(callback));worker.reconcile(id);
        when(safety.atomic()).thenReturn(true);when(calendar.atomic()).thenReturn(true);
        when(policy.read(any())).thenReturn(Optional.of(new RenewalOperationSources.Policy("test","v1",Duration.ofDays(2),Duration.ofHours(1))));
        when(safety.check(any(),any(),any(),any())).thenReturn(NOW.plusSeconds(172800));
        when(calendar.feasible(any(Renewal.class),any(),any())).thenReturn(true);
        when(finance.consistentThroughApproval()).thenReturn(true);
        when(finance.read(any())).thenReturn(Optional.of(new RenewalSources.Financial(NOW,List.of(),false)));
        worker.reconcile(id);worker.reconcile(id);
        assertThat(receiptCount(id)).isEqualTo(1);assertThat(read(id).getRenewalSigningPreparedAt()).as(read(id).getFailureReason()).isNotNull();
        var sources=new org.springframework.beans.factory.support.StaticListableBeanFactory();sources.addBean("finance",finance);
        var accounting=new RenewalLedgerAccountingConfiguration.Accounting(em,ledger,payments,
            provenance,sources.getBeanProvider(RenewalSources.FinancialSource.class));
        tx.executeWithoutResult(s->{var p=payments.findById(id).orElseThrow();assertThat(p.getRenewal().getStatus()).isEqualTo(RenewalStatus.full_rent_paid);
            assertThat(p.getRenewal().getRental().getContractEndDate()).isEqualTo(LocalDate.of(2026,10,31));
            var terms=p.getRenewalRevision().getQuote().getTermsJson();
            try { accounting.requireFullyPaid(p.getRenewal(),mapper.readValue(terms,RenewalQuoteResponse.Terms.class),NOW.plusSeconds(3600)); }
            catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        });
        tx.executeWithoutResult(s->{var p=payments.findById(id).orElseThrow();p.setRefundedAmount(BigDecimal.ONE);});
        assertThatThrownBy(()->tx.executeWithoutResult(s->{var p=payments.findById(id).orElseThrow();
            try {accounting.requireFullyPaid(p.getRenewal(),mapper.readValue(p.getRenewalRevision().getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class),NOW.plusSeconds(3600));}
            catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        })).hasMessageContaining("Verified Payment allocation");
    }
    @Test void afterCommitListenerAllocatesAndPersistentQueueFindsBlockedSigning() throws Exception {
        UUID id=fixture();var callback=callback(id,Map.of());
        org.springframework.test.util.ReflectionTestUtils.setField(gateway,"paymentEvents",events);
        tx.executeWithoutResult(s->{gateway.processCallback(callback);assertThat(receiptCount(id)).isZero();});
        assertThat(read(id).getStatus()).isEqualTo(PaymentStatus.PAID);assertThat(receiptCount(id)).isEqualTo(1);
        tx.executeWithoutResult(s->{var payment=payments.findById(id).orElseThrow();payment.setRenewalNextRetryAt(Instant.now().minusSeconds(1));});
        assertThat(payments.findRenewalReconciliationCandidates(Instant.now(),org.springframework.data.domain.PageRequest.of(0,100))).contains(id);
        worker.retryPending();assertThat(receiptCount(id)).isEqualTo(1);
    }
    @Test void wrongAmountMerchantAndRevisionCannotAllocate() throws Exception {
        UUID id=fixture();var wrong=callback(id,Map.of("vnp_Amount","1"));tx.executeWithoutResult(s->gateway.processCallback(wrong));
        assertThat(read(id).getStatus()).isEqualTo(PaymentStatus.PENDING);
        var merchant=callback(id,Map.of("vnp_TmnCode","OTHER"));tx.executeWithoutResult(s->gateway.processCallback(merchant));
        assertThat(read(id).getStatus()).isEqualTo(PaymentStatus.PENDING);
        var right=callback(id,Map.of());tx.executeWithoutResult(s->gateway.processCallback(right));
        tx.executeWithoutResult(s->{var p=payments.findById(id).orElseThrow();p.getRenewal().setStatus(RenewalStatus.cancelled);});
        worker.reconcile(id);assertThat(receiptCount(id)).isZero();assertThat(read(id).getStatus()).isEqualTo(PaymentStatus.PAID);
    }
    @Test void signingExpiryForfeitsVerifiedDepositOnceWithoutExtendingTheRental() throws Exception {
        UUID payment=paidDeposit(false);var source=depositSource(NOW.plus(Duration.ofDays(29)));
        UUID renewalId=tx.execute(s->payments.findById(payment).orElseThrow().getRenewal().getId());
        tx.executeWithoutResult(s->{assertThat(source.reconcile(renewalId)).isTrue();assertThat(source.reconcile(renewalId)).isFalse();
            assertThat(source.read(renewalId).status()).isEqualTo("FORFEITED");
            var n=em.find(Renewal.class,renewalId);assertThat(n.getRental().getContractEndDate()).isEqualTo(LocalDate.of(2026,10,31));
            assertThat(n.getRental().getStorageUnit().getStatus()).isEqualTo(StorageUnitStatus.occupied);
            assertThat(ledger.read(n.getRental().getId()).orElseThrow().knownOutstanding()).isZero();
        });
    }
    @Test void expiredSigningDoesNotForfeitDepositBeforeDayEightRecoveryStart() throws Exception {
        UUID payment=paidDeposit(false);var source=depositSource(NOW.plus(Duration.ofDays(4)));
        tx.executeWithoutResult(s->{
            var n=payments.findById(payment).orElseThrow().getRenewal();
            assertThat(source.reconcile(n.getId())).isFalse();
            assertThat(source.read(n.getId()).status()).isEqualTo("AWAITING_RECOVERY_DEADLINE");
            assertThat(jdbc.queryForObject("select count(*) from renewal_deposit_forfeitures where renewal_id=?",Long.class,n.getId().toString())).isZero();
            assertThat(n.getStatus()).isEqualTo(RenewalStatus.deposit_paid);
        });
    }
    @Test void unresolvedFacilityIncidentPreventsAutomaticDepositForfeiture() throws Exception {
        UUID payment=paidDeposit(false);var source=depositSource(NOW.plus(Duration.ofDays(29)));
        tx.executeWithoutResult(s->{var n=payments.findById(payment).orElseThrow().getRenewal();incident(n,false);
            assertThat(source.reconcile(n.getId())).isFalse();assertThat(source.read(n.getId()).status()).isEqualTo("AWAITING_FAULT_REVIEW");});
    }
    @Test void depositSettlementAfterRecoveryPreservesANewerRenewalOpenSlot() throws Exception {
        UUID payment=paidDeposit(false);var source=depositSource(NOW.plus(Duration.ofDays(29)));
        tx.executeWithoutResult(s->{
            var old=payments.findById(payment).orElseThrow().getRenewal();
            em.remove(em.find(RenewalOpenSlot.class,old.getRental().getId()));em.flush();
            var next=new Renewal();next.setRental(old.getRental());next.setRequestedBy(old.getRequestedBy());next.setStatus(RenewalStatus.accepted);
            next.setAmount(old.getAmount());next.setNewEndDate(old.getNewEndDate());em.persist(next);em.persist(new RenewalOpenSlot(old.getRental(),next));em.flush();
            assertThat(source.reconcile(old.getId())).isTrue();
            assertThat(em.find(RenewalOpenSlot.class,old.getRental().getId()).getRenewal().getId()).isEqualTo(next.getId());
        });
    }
    @Test void refundReservationAndSandboxAttestationAreCommittedOnceAndNeverBecomeProductionPayouts() throws Exception {
        UUID payment=paidDeposit(false);Instant now=NOW.plus(Duration.ofDays(4));var source=depositSource(now);
        UUID refund=tx.execute(s->{var n=payments.findById(payment).orElseThrow().getRenewal();var incident=incident(n,true);
            var reserved=source.reserve(n,incident,n.getRequestedBy().getId(),"test-refund-"+n.getId(),now);
            assertThat(source.read(n.getId()).status()).isEqualTo("REFUND_APPROVED");
            assertThat(n.getStatus()).isEqualTo(RenewalStatus.cancelled);assertThat(n.getRental().getContractEndDate()).isEqualTo(LocalDate.of(2026,10,31));
            return reserved.reference();});
        assertThat(read(payment).getRefundedAmount()).isZero();
        assertThatThrownBy(()->tx.executeWithoutResult(s->{var p=payments.findById(payment).orElseThrow();
            source.execute(p.getRenewal(),refund,UUID.randomUUID(),"same-proposer",p.getInitiatedBy().getId(),now.plusSeconds(1));}))
            .hasMessageContaining("proposer cannot execute");
        UUID executor=tx.execute(s->{var user=new User();user.setEmail(UUID.randomUUID()+"@test.invalid");
            user.setFullName("TEST refund executor");user.setPasswordHash("TEST-ONLY");em.persist(user);em.flush();return user.getId();});
        assertThatThrownBy(()->tx.executeWithoutResult(s->{var p=payments.findById(payment).orElseThrow();var account=ledger.read(p.getRenewal().getRental().getId()).orElseThrow();
            ledger.executeRefund(account.rentalId(),account.refunds().getFirst().id(),"production-payout",p.getInitiatedBy().getId(),"forbidden-production-payout",account.revision());})).hasMessageContaining("Sandbox refund");
        tx.executeWithoutResult(s->{var p=payments.findById(payment).orElseThrow();var done=source.execute(p.getRenewal(),refund,UUID.randomUUID(),"sandbox-refund-"+refund,executor,now.plusSeconds(1));
            assertThat(done.environment()).isEqualTo("SANDBOX");assertThat(done.amount()).isEqualByComparingTo("40");
            assertThat(source.read(p.getRenewal().getId()).status()).isEqualTo("REFUNDED");});
        assertThat(read(payment).getRefundedAmount()).isEqualByComparingTo("40");
        tx.executeWithoutResult(s->{var p=payments.findById(payment).orElseThrow();assertThat(ledger.read(p.getRenewal().getRental().getId()).orElseThrow().knownOutstanding()).isZero();});
        assertThatThrownBy(()->tx.executeWithoutResult(s->{var p=payments.findById(payment).orElseThrow();source.execute(p.getRenewal(),refund,UUID.randomUUID(),"duplicate",executor,now.plusSeconds(2));})).hasMessageContaining("awaiting execution");
    }
    @Test void dailyFeesUseActualAllocationsAndASecondDayCanBePaidWithoutPayingRentAgain() throws Exception {
        UUID payment=paidDeposit(true);Instant first=NOW.plus(Duration.ofDays(1)).plusSeconds(3600),second=NOW.plus(Duration.ofDays(2));
        var provider=new org.springframework.beans.factory.support.StaticListableBeanFactory();provider.addBean("finance",finance);
        var accounting=new RenewalLedgerAccountingConfiguration.Accounting(em,ledger,payments,provenance,provider.getBeanProvider(RenewalSources.FinancialSource.class));
        org.springframework.test.util.ReflectionTestUtils.setField(accounting,"manualPayments",new ManualRentalPaymentService(payments,audit));
        var periods=mock(com.storagehub.service.rental.period.RentalPeriodResolver.class);
        when(periods.require(any())).thenAnswer(call->{var r=(Rental)call.getArgument(0);return com.storagehub.service.rental.period.RentalPeriod.verified(r.getId(),r.getStartDate(),r.getContractEndDate(),com.storagehub.service.rental.period.RentalPeriod.Convention.INCLUSIVE,"period").orElseThrow();});
        var policyService=mock(com.storagehub.service.renewal.integration.RenewalPolicyPublicationService.class);var published=mock(com.storagehub.api.renewal.PublishedRenewalPolicy.class);
        when(published.term()).thenReturn(new com.storagehub.api.renewal.PublishedRenewalPolicy.Term("calendar","Asia/Ho_Chi_Minh",3,6,7,8,LocalTime.of(17,0),LocalTime.MIDNIGHT));
        when(policyService.read(any(),any())).thenReturn(Optional.of(new com.storagehub.service.renewal.integration.RenewalPolicyPublicationService.Stored("policy",published)));
        org.springframework.test.util.ReflectionTestUtils.setField(accounting,"fees",new RenewalOverdueFeeService(em,periods,policyService));
        tx.executeWithoutResult(s->{var rows=em.createQuery("select x from SystemSetting x where x.settingKey='lateFeeAmount'",SystemSetting.class).getResultList();
            var setting=rows.isEmpty()?new SystemSetting():rows.getFirst();setting.setSettingKey("lateFeeAmount");setting.setValue("5");setting.setSettingType("number");setting.setGroupName("test");setting.setLabel("test");if(rows.isEmpty())em.persist(setting);});
        tx.executeWithoutResult(s->{var p=payments.findById(payment).orElseThrow();var n=p.getRenewal();var terms=decodeTerms(p);
            var statement=accounting.statement(n,terms,first);assertThat(statement.remainingRentalAmount()).isEqualByComparingTo("60");assertThat(statement.overdueFee()).isEqualByComparingTo("5");
            recordFeeCash(accounting,n,statement,"fee-day-one",first);accounting.requireFullyPaid(n,terms,first);
        });
        assertThatThrownBy(()->tx.executeWithoutResult(s->{var p=payments.findById(payment).orElseThrow();accounting.requireFullyPaid(p.getRenewal(),decodeTerms(p),second);})).hasMessageContaining("Daily overdue fee");
        tx.executeWithoutResult(s->{var p=payments.findById(payment).orElseThrow();var n=p.getRenewal();var terms=decodeTerms(p);var statement=accounting.statement(n,terms,second);
            assertThat(statement.remainingRentalAmount()).isZero();assertThat(statement.overdueFee()).isEqualByComparingTo("5");
            recordFeeCash(accounting,n,statement,"fee-day-two",second);accounting.requireFullyPaid(n,terms,second);
            var manual=payments.findAllByRenewal_IdAndStatusIn(n.getId(),List.of(PaymentStatus.PAID)).stream().filter(v->v.getPurpose()==PaymentType.RENTAL_BALANCE).toList();
            assertThat(manual).hasSize(2);assertThat(manual.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("70");
        });
    }
    private RenewalQuoteResponse.Terms decodeTerms(Payment payment){try{return mapper.readValue(payment.getRenewalRevision().getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class);}catch(Exception e){throw new IllegalStateException(e);}}
    private void recordFeeCash(RenewalLedgerAccountingConfiguration.Accounting source,Renewal n,RenewalOperationSources.Statement statement,String reference,Instant now) {
        var f=new FileAsset();f.setEntityType("DUONG_RENEWAL_CASH_RECEIPT");f.setEntityId(n.getId());f.setUploadedBy(n.getRequestedBy());
        f.setOriginalName(reference+".pdf");f.setStorageKey("test-only/"+reference);f.setContentType("application/pdf");f.setSizeBytes(1L);f.setChecksumSha256("0".repeat(64));em.persist(f);em.flush();
        var receipt=source.balance(n,statement.reference(),reference,f.getId(),"CASH",n.getRequestedBy().getId(),reference+":"+n.getId(),now);
        try{em.persist(new com.storagehub.service.renewal.operations.RenewalOperationEvent(n,"CASH",now,n.getRequestedBy().getId(),mapper.writeValueAsString(receipt)));em.flush();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private UUID paidDeposit(boolean overdue) throws Exception {
        UUID id=fixture(true,overdue);when(safety.atomic()).thenReturn(true);when(calendar.atomic()).thenReturn(true);
        when(policy.read(any())).thenReturn(Optional.of(new RenewalOperationSources.Policy("test","v1",Duration.ofDays(3),Duration.ofHours(1))));
        when(safety.check(any(),any(),any(),any())).thenReturn(NOW.plus(Duration.ofDays(5)));
        when(calendar.feasible(any(Renewal.class),any(),any())).thenReturn(true);
        when(finance.consistentThroughApproval()).thenReturn(true);when(finance.read(any())).thenReturn(Optional.of(new RenewalSources.Financial(NOW,List.of(),false)));
        tx.executeWithoutResult(s->gateway.processCallback(callbackUnchecked(id)));worker.reconcile(id);
        assertThat(read(id).getRenewalSigningPreparedAt()).as(read(id).getFailureReason()).isNotNull();
        tx.executeWithoutResult(s->{var n=payments.findById(id).orElseThrow().getRenewal();n.getRental().getStorageUnit().setStatus(StorageUnitStatus.occupied);
            var state=em.find(com.storagehub.service.renewal.operations.RenewalOperationState.class,n.getId());
            var slot=new RenewalOperationSources.Slot("slot",NOW.plusSeconds(7200),NOW.plusSeconds(9000));
            try{var event=new com.storagehub.service.renewal.operations.RenewalOperationEvent(n,"APPOINTMENT",NOW,null,mapper.writeValueAsString(slot));em.persist(event);em.flush();state.appointment(event.getId(),slot.start(),slot.end());}
            catch(Exception e){throw new IllegalStateException(e);}
        });return id;
    }
    private UUID incident(Renewal n,boolean reviewed) {
        var state=em.find(com.storagehub.service.renewal.operations.RenewalOperationState.class,n.getId());
        try {var event=new com.storagehub.service.renewal.operations.RenewalOperationEvent(n,"INCIDENT",NOW.plusSeconds(7200),n.getRequestedBy().getId(),mapper.writeValueAsString(Map.of("appointmentRef",state.getAppointmentRef())));em.persist(event);em.flush();
            if(reviewed)em.persist(new com.storagehub.service.renewal.operations.RenewalOperationEvent(n,"FAULT_REVIEW",NOW.plusSeconds(8000),n.getRequestedBy().getId(),mapper.writeValueAsString(Map.of("incidentId",event.getId(),"facilityFault",true))));
            em.flush();return event.getId();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private RenewalDepositSettlementService depositSource(Instant now) {
        var sources=new org.springframework.beans.factory.support.StaticListableBeanFactory();sources.addBean("calendar",calendar);sources.addBean("safety",safety);sources.addBean("policy",policy);
        sources.addBean("clock",Clock.fixed(now,ZoneOffset.UTC));sources.addBean("operations",mock(com.storagehub.service.renewal.operations.RenewalOperationService.class));
        sources.addBean("credits",new RenewalLedgerAdjustments(jdbc));
        sources.addBean("forfeitures",new RenewalDepositForfeitures(jdbc));
        var termSource=mock(com.storagehub.service.overdue.OverdueSources.TermSource.class);
        when(termSource.term(any(),any())).thenAnswer(call->{
            var rental=(Rental)call.getArgument(0);var end=rental.getContractEndDate();var zone=ZoneId.of("Asia/Ho_Chi_Minh");
            return Optional.of(new com.storagehub.service.overdue.OverdueSources.Term("test","v1",end,3,6,7,8,
                end.plusDays(7).atTime(17,0).atZone(zone).toInstant(),end.plusDays(8).atTime(8,0).atZone(zone).toInstant()));
        });
        sources.addBean("terms",termSource);
        org.springframework.test.util.ReflectionTestUtils.setField(ledger,"cancellationCredits",sources.getBeanProvider(RenewalLedgerAdjustments.class));
        var source=new RenewalDepositSettlementService(em,mapper,ledger,payments,provenance,new RenewalPersistence(em,mapper),audit,mock(NotificationService.class),
            sources.getBeanProvider(Clock.class),sources.getBeanProvider(RenewalOperationSources.PolicySource.class),sources.getBeanProvider(RenewalOperationSources.CalendarSource.class),
            sources.getBeanProvider(RenewalOperationSources.SafetySource.class),sources.getBeanProvider(com.storagehub.service.renewal.operations.RenewalOperationService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(source,"cancellationCredits",sources.getBeanProvider(RenewalLedgerAdjustments.class));
        org.springframework.test.util.ReflectionTestUtils.setField(source,"forfeitures",sources.getBeanProvider(RenewalDepositForfeitures.class));
        org.springframework.test.util.ReflectionTestUtils.setField(source,"overdueTerms",sources.getBeanProvider(com.storagehub.service.overdue.OverdueSources.TermSource.class));
        org.springframework.test.util.ReflectionTestUtils.setField(source,"faultGuard",new RenewalDepositFaultGuard(
            em,mapper,mock(com.storagehub.service.integration.DuongResourceAccess.class)));
        return source;
    }
    Payment read(UUID id){return payments.findById(id).orElseThrow();}
    long receiptCount(UUID id){return jdbc.queryForObject("select count(*) from rental_ledger_receipts where evidence_ref=?",Long.class,"payment:"+id);}
    Map<String,String> callback(UUID id,Map<String,String> changes) throws Exception {
        var q=new TreeMap<String,String>();q.put("vnp_TxnRef",read(id).getGatewayIntentId());q.put("vnp_Amount",read(id).getAmount().movePointRight(2).toPlainString().split("\\.")[0]);q.put("vnp_TmnCode","TEST");
        q.put("vnp_ResponseCode","00");q.put("vnp_TransactionStatus","00");q.put("vnp_TransactionNo","test-"+id);q.put("vnp_PayDate","20261011080000");q.putAll(changes);
        var data=q.entrySet().stream().map(e->java.net.URLEncoder.encode(e.getKey(),java.nio.charset.StandardCharsets.UTF_8)+"="+java.net.URLEncoder.encode(e.getValue(),java.nio.charset.StandardCharsets.UTF_8)).reduce((a,b)->a+"&"+b).orElseThrow();
        var mac=Mac.getInstance("HmacSHA512");mac.init(new SecretKeySpec("test-only".getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA512"));
        q.put("vnp_SecureHash",HexFormat.of().formatHex(mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8))));return q;
    }
    UUID fixture() throws Exception {return fixture(false,false);}
    UUID fixture(boolean deposit,boolean overdue) throws Exception {
        return tx.execute(s->{
            var rental=rentalFixture();if(overdue)rental.setContractEndDate(LocalDate.of(2026,10,10));
            var start=rental.getContractEndDate().plusDays(1);var end=start.plusMonths(1);
            var n=new Renewal();n.setRental(rental);n.setRequestedBy(rental.getCustomer());n.setStatus(RenewalStatus.accepted);n.setAmount(new BigDecimal("100"));n.setNewEndDate(end.minusDays(1));em.persist(n);
            var terms=new RenewalQuoteResponse.Terms(rental.getContractEndDate(),start,end,n.getNewEndDate(),rental.getStorageUnit().getUnitType().getId(),"TEST",UUID.randomUUID(),"v1",1,new BigDecimal("100"),BigDecimal.ZERO,new BigDecimal("100"),BigDecimal.ZERO,new BigDecimal("100"),deposit?new BigDecimal("40"):BigDecimal.ZERO,deposit?new BigDecimal("60"):new BigDecimal("100"),"VND","test","v1",rental.getStorageUnit().getId(),rental.getFacility().getId());
            try {
                String json=mapper.writeValueAsString(terms);String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var quote=new RenewalQuote(rental,rental.getCustomer(),json,hash,NOW.minusSeconds(120),NOW.plusSeconds(120));em.persist(quote);
                var revision=new RenewalAcceptedRevision(n,quote,1,NOW,null);em.persist(revision);
                var workflow=new RenewalWorkflow(n,revision);workflow.paymentReady(NOW.plusSeconds(3600),UUID.randomUUID());em.persist(workflow);em.persist(new RenewalOpenSlot(rental,n));em.flush();
                ledger.open(rental.getId(),rental.getCustomer().getId(),rental.getFacility().getId());
                var spec=FinancialContractSpecifications.accepted(revision,terms).getFirst();ledger.charge(rental.getId(),n.getId(),spec.kind(),spec.amount(),workflow.getPaymentDeadline(),spec.sourceReference(),rental.getCustomer().getId(),(deposit?"renewal-prepayment:":"renewal-full-rent:")+revision.getId(),0);
                if(deposit) {
                    var remainder=FinancialContractSpecifications.accepted(revision,terms).getLast();
                    workflow.signingBalanceDue(NOW.plusSeconds(9000));
                    ledger.charge(rental.getId(),n.getId(),remainder.kind(),remainder.amount(),workflow.getSigningBalanceDueAt(),remainder.sourceReference(),rental.getCustomer().getId(),"renewal-remainder:"+revision.getId(),1);
                }
                var p=new Payment();p.setInitiatedBy(rental.getCustomer());p.setRenewal(n);p.setRenewalRevision(revision);p.setPaymentDeadline(workflow.getPaymentDeadline());p.setAmount(new BigDecimal(deposit?"40":"100"));p.setCurrency("VND");p.setPurpose(PaymentType.RENEWAL);p.setGatewayProvider("VNPAY");p.setGatewayIntentId("test-"+UUID.randomUUID());p.setIdempotencyKey(UUID.randomUUID().toString());payments.saveAndFlush(p);return p.getId();
            } catch(Exception e){throw new IllegalStateException(e);}
        });
    }
    private Rental rentalFixture(){
        String fixtureKey=UUID.randomUUID().toString().substring(0,8);
        Facility f=new Facility();f.setCode("TEST-"+fixtureKey);f.setName("Test");f.setAddress("Test");f.setCity("Test");em.persist(f);
        User u=new User();u.setEmail(fixtureKey+"@test.invalid");u.setFullName("Test Customer");u.setPasswordHash("test-only");em.persist(u);
        UnitType t=new UnitType();t.setFacility(f);t.setCode("M");t.setName("Medium");t.setLengthM(BigDecimal.ONE);t.setWidthM(BigDecimal.ONE);t.setHeightM(BigDecimal.ONE);t.setMonthlyPrice(new BigDecimal("100"));t.setMaxLoadKg(BigDecimal.ONE);t.setRackLengthM(BigDecimal.ONE);t.setRackWidthM(BigDecimal.ONE);t.setRackHeightM(BigDecimal.ONE);em.persist(t);
        StorageUnit unit=new StorageUnit();unit.setFacility(f);unit.setUnitType(t);unit.setCode("TEST-"+fixtureKey);em.persist(unit);
        ReservationQuote q=com.storagehub.testing.LegacyReservationQuoteFixture.create();q.setCustomer(u);q.setFacility(f);q.setUnitType(t);q.setPricingPackageCode("TEST");q.setPolicyVersion("test-only");q.setStartDate(LocalDate.of(2026,10,1));q.setEndDate(LocalDate.of(2026,11,1));q.setRentalMonths(1);q.setMonthlyPrice(new BigDecimal("100"));q.setSubtotal(new BigDecimal("100"));q.setDiscountRate(BigDecimal.ZERO);q.setDiscountAmount(BigDecimal.ZERO);q.setTotalAfterDiscount(new BigDecimal("100"));q.setReservationDepositAmount(BigDecimal.ZERO);q.setSecurityDepositAmount(BigDecimal.ZERO);q.setRemainingRentalAmount(new BigDecimal("100"));q.setDueAtCheckIn(new BigDecimal("100"));q.setTotalInitialObligation(new BigDecimal("100"));q.setQuotedAt(NOW);q.setExpiresAt(NOW.plusSeconds(1800));em.persist(q);
        Reservation res=new Reservation();res.setReservationCode("TEST-"+fixtureKey);res.setCustomer(u);res.setSourceQuote(q);res.setIdempotencyKey("TEST-"+fixtureKey);res.setFacility(f);res.setUnitType(t);res.setStartDate(q.getStartDate());res.setEndDate(q.getEndDate());em.persist(res);
        Rental r=new Rental();r.setCustomer(u);r.setFacility(f);r.setStorageUnit(unit);r.setReservation(res);r.setStartDate(q.getStartDate());r.setContractEndDate(LocalDate.of(2026,10,31));r.setMonthlyPrice(new BigDecimal("100"));em.persist(r);em.flush();return r;
    }
}
