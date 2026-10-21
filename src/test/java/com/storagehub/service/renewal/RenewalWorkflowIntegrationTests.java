package com.storagehub.service.renewal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.*;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.*;
import com.storagehub.domain.model.Role;
import com.storagehub.security.*;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.renewal.persistence.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest @ActiveProfiles("test")
@Import({com.storagehub.testsupport.DisplayCodeTestConfiguration.class,RenewalWorkflowService.class,RenewalPersistence.class,RenewalReadService.class,AuditLogService.class,RenewalWorkflowIntegrationTests.Sources.class})
class RenewalWorkflowIntegrationTests {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.storagehub.service.NotificationService workflowNotifications;
    static final Instant NOW=Instant.parse("2026-10-06T00:00:00Z");
    static final UUID PACKAGE=UUID.fromString("00000000-0000-0000-0000-000000000001");
    @TestConfiguration static class Sources {
        @Bean com.storagehub.service.RentalReadSources.DateSource dateEvidence(){return r -> Optional.of(new com.storagehub.service.RentalReadSources.Dates(r.getId(),r.getStartDate(),r.getContractEndDate(),"TEST-ONLY-inclusive-period"));}
        @Bean com.storagehub.service.rental.period.RentalPeriodResolver periods(org.springframework.beans.factory.ObjectProvider<com.storagehub.service.RentalReadSources.DateSource> sources){return new com.storagehub.service.rental.period.RentalPeriodResolver(sources);}
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean ActorContext actorContext(){return mock(ActorContext.class);}
        @Bean TestClock clock(){return new TestClock();}
        @Bean TestPolicy policy(){return new TestPolicy();}
        @Bean TestEligibility eligibility(){return new TestEligibility();}
        @Bean TestPrice prices(){return new TestPrice();}
        @Bean TestFinance finance(){return new TestFinance();}
        @Bean TestHold hold(){return new TestHold();}
        @Bean TestLifecycle lifecycle(){return new TestLifecycle();}
    }
    static class TestPrice implements RenewalSources.PricingSource {
        BigDecimal rate=new BigDecimal("100");
        public Optional<List<RenewalSources.Price>> read(Rental r,LocalDate start){return Optional.of(List.of(new RenewalSources.Price(PACKAGE,"TEST","v1",r.getStorageUnit().getUnitType().getId(),1,rate,BigDecimal.ZERO,"VND",2)));}
    }
    static class TestPolicy implements RenewalSources.PolicySource {
        String version="v1";
        public Optional<RenewalSources.Policy> read(Rental r,LocalDate date){return Optional.of(new RenewalSources.Policy("test-policy",version,Duration.ofMinutes(30),Duration.ofHours(24),30,new BigDecimal("0.2"),Set.of(PACKAGE)));}
    }
    static class TestEligibility implements RenewalSources.EligibilitySource {
        boolean unknown;
        public Optional<RenewalSources.Eligibility> read(Rental r,Instant now){return unknown?Optional.empty():Optional.of(new RenewalSources.Eligibility(true,false,true,NOW.plusSeconds(172800)));}
    }
    static class TestFinance implements RenewalSources.FinancialSource {
        boolean unknown=false;boolean debt=false;boolean consistent=true;
        public boolean consistentThroughApproval(){return consistent;}
        public Optional<RenewalSources.Financial> read(Rental r){return unknown?Optional.empty():Optional.of(new RenewalSources.Financial(NOW,debt?List.of(PACKAGE):List.of(),false));}
    }
    static class TestLifecycle implements RenewalSources.ApprovalLifecycleSource {
        boolean available=true;
        public boolean ready(Rental rental){return available;}
    }
    static class TestClock extends Clock {
        Instant time=NOW;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return Clock.fixed(time,zone);}public Instant instant(){return time;}
    }
    static class TestHold implements RenewalSources.ExtensionHoldSource {
        @Autowired EntityManager em;
        int calls;boolean atomic=true;boolean fail=false;
        public boolean participatesInTransaction(){return atomic;}
        public UUID acquire(Rental r,UUID id,LocalDate start,LocalDate end,Instant expiry){calls++;
            var marker=new ActivityLog();marker.setAction("test_hold");marker.setEntityType("TestHold");marker.setEntityId(id);marker.setFacility(r.getFacility());marker.setCorrelationId("test-hold-fixture");em.persist(marker);em.flush();
            if(fail)throw new IllegalStateException("Test-only hold failure");
            return UUID.fromString("00000000-0000-0000-0000-000000000002");}
    }
    @Autowired EntityManager em;@Autowired RenewalWorkflowService commands;@Autowired RenewalReadService reads;
    @Autowired TestPrice pricing;@Autowired TestPolicy policy;@Autowired TestEligibility eligibility;@Autowired TestFinance finance;@Autowired TestHold hold;
    @Autowired TestLifecycle lifecycle;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired javax.sql.DataSource datasource;
    @Autowired TestClock clock;
    @Autowired com.storagehub.domain.repo.ReservationPricingSnapshotRepository pricingSnapshots;
    @Autowired com.storagehub.domain.repo.PaymentRepository paymentHistory;
    @Autowired com.storagehub.domain.repo.ActivityLogRepository activityLogs;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.storagehub.service.ledger.LedgerStore ledgerWrites;
    @BeforeEach void resetMutableTerms(){pricing.rate=new BigDecimal("100");policy.version="v1";}
    @Test void httpCommandsExposeRealResultsAndRejectInjectedFields() throws Exception {
        var r=fixture();var a=customer(r);var context=mock(ActorContext.class);when(context.required()).thenReturn(a);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new CustomerRenewalController(context,reads,commands),new ManagerRenewalController(context,reads))
            .setControllerAdvice(new RenewalRequestAdvice(),new com.storagehub.common.api.GlobalExceptionHandler()).build();
        String path="/api/customer/rentals/"+r.getId();
        mvc.perform(post(path+"/renewal-quote").contentType("application/json").content("{\"pricingPackageCode\":\"TEST\",\"amount\":1}")).andExpect(status().isBadRequest());
        var quote=mvc.perform(post(path+"/renewal-quote").contentType("application/json").content("{\"pricingPackageCode\":\"TEST\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("data.monthlyPrice").value(100)).andReturn();
        String quoteId=new ObjectMapper().readTree(quote.getResponse().getContentAsString()).path("data").path("id").asText();
        mvc.perform(post(path+"/renewal-requests").contentType("application/json").content("{\"renewalQuoteId\":\""+quoteId+"\"}")).andExpect(status().isBadRequest());
        String body="{\"renewalQuoteId\":\""+quoteId+"\"}";
        mvc.perform(post(path+"/renewal-requests").header("Idempotency-Key","http").contentType("application/json").content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("data.status").value("accepted")).andExpect(jsonPath("data.version").value(0));
        mvc.perform(post(path+"/renewal-requests").header("Idempotency-Key","http").contentType("application/json").content(body)).andExpect(status().isCreated());
        assertThat(em.createQuery("select count(x) from Renewal x",Long.class).getSingleResult()).isEqualTo(1);
    }
    @BeforeEach void resetSources(){pricing.rate=new BigDecimal("100");eligibility.unknown=false;finance.unknown=false;finance.debt=false;finance.consistent=true;lifecycle.available=true;hold.calls=0;hold.atomic=true;hold.fail=false;clock.time=NOW;}
    @Test void optionsRemainReadableWhenEligibilityIsUnknownButQuoteStaysBlocked(){
        var r=fixture();var actor=customer(r);eligibility.unknown=true;
        assertThat(commands.options(actor,r.getId())).containsExactly(new RenewalCommands.Option("TEST",1));
        assertThatThrownBy(()->commands.quote(actor,r.getId(),new RenewalCommands.Quote("TEST")))
            .isInstanceOf(ApiException.class).hasMessageContaining("Eligibility UNKNOWN");
        assertThat(em.createQuery("select count(q) from RenewalQuote q",Long.class).getSingleResult()).isZero();
    }
    @Test void acceptedTermsSurviveReloadAndCurrentPriceChange(){
        var r=fixture();var a=customer(r);var q=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));
        var n=commands.submit(a,r.getId(),new RenewalCommands.Submit(q.id(),null),"submit");
        pricing.rate=new BigDecimal("200");em.clear();
        var detail=reads.detail(a,n.id(),false);
        assertThat(detail.acceptedTerms()).usingRecursiveComparison()
            .withComparatorForType(BigDecimal::compareTo,BigDecimal.class).isEqualTo(q.terms());
        assertThat(detail.acceptedTerms().monthlyPrice()).isEqualByComparingTo("100");
        assertThat(detail.acceptedTerms().renewalDepositAmount()).isEqualByComparingTo("20");
        assertThat(detail.acceptedTerms().remainingRentalAmount()).isEqualByComparingTo("80");
        assertThat(detail.reviewState()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
    }
    @Test void mismatchedPersistedAmountFailsReadWithoutRepair(){
        var r=fixture();var a=customer(r);var q=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));
        var n=commands.submit(a,r.getId(),new RenewalCommands.Submit(q.id(),null),"submit");
        em.find(Renewal.class,n.id()).setAmount(new BigDecimal("999"));em.flush();
        assertThatThrownBy(()->reads.detail(a,n.id(),false)).isInstanceOf(ApiException.class);
        assertThat(em.find(Renewal.class,n.id()).getAmount()).isEqualByComparingTo("999");
    }
    @Test @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void concurrentDifferentKeysProduceOneRequest() throws Exception {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);var r=tx.execute(s->fixture());var a=customer(r);
        var q=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));var body=new RenewalCommands.Submit(q.id(),null);
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(String key:List.of("a","b"))futures.add(pool.submit(()->{start.await();try{commands.submit(a,r.getId(),body,key);return true;}catch(ApiException e){assertThat(e.getStatus().value()).isEqualTo(409);return false;}}));
            start.countDown();int successes=0;for(var f:futures)if(f.get(30,java.util.concurrent.TimeUnit.SECONDS))successes++;
            assertThat(successes).isEqualTo(1);
            tx.executeWithoutResult(s->{assertThat(em.createQuery("select count(x) from Renewal x",Long.class).getSingleResult()).isEqualTo(1);assertThat(em.createQuery("select count(x) from RenewalOpenSlot x",Long.class).getSingleResult()).isEqualTo(1);});
        }finally{pool.shutdownNow();}
    }
    @Test void staleQuoteCannotBeSubmittedOrAcceptedAsRevision(){
        var r=fixture();var a=customer(r);
        var stale=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));
        pricing.rate=new BigDecimal("150");
        assertThatThrownBy(()->commands.submit(a,r.getId(),new RenewalCommands.Submit(stale.id(),null),"stale-submit"))
            .isInstanceOf(ApiException.class).hasMessageContaining("new quote and Customer confirmation");
        assertThat(em.createQuery("select count(n) from Renewal n",Long.class).getSingleResult()).isZero();
        var fresh=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));
        var n=commands.submit(a,r.getId(),new RenewalCommands.Submit(fresh.id(),null),"fresh-submit");
        assertThat(n.amount()).isEqualByComparingTo("150");
        var revision=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));
        policy.version="v2";
        assertThatThrownBy(()->commands.edit(a,n.id(),new RenewalCommands.Edit(revision.id(),"consent",n.version()),"stale-edit"))
            .isInstanceOf(ApiException.class).hasMessageContaining("new quote and Customer confirmation");
        assertThat(reads.detail(a,n.id(),false).acceptedRevision()).isEqualTo(1);
        var revised=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));
        assertThat(commands.edit(a,n.id(),new RenewalCommands.Edit(revised.id(),"consent",n.version()),"fresh-edit").acceptedRevision()).isEqualTo(2);
    }
    @Test @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void stalePolicyQuoteHttpRejectsAndFreshQuoteCommitsWithJdbcReadback() throws Exception {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var r=tx.execute(s->fixture());var a=customer(r);var context=mock(ActorContext.class);when(context.required()).thenReturn(a);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new CustomerRenewalController(context,reads,commands))
            .setControllerAdvice(new RenewalRequestAdvice(),new com.storagehub.common.api.GlobalExceptionHandler()).build();
        String path="/api/customer/rentals/"+r.getId();
        var quoted=mvc.perform(post(path+"/renewal-quote").contentType("application/json").content("{\"pricingPackageCode\":\"TEST\"}"))
            .andExpect(status().isOk()).andReturn();
        String staleId=new ObjectMapper().readTree(quoted.getResponse().getContentAsString()).path("data").path("id").asText();
        policy.version="v2";
        mvc.perform(post(path+"/renewal-requests").header("Idempotency-Key","stale-policy").contentType("application/json")
            .content("{\"renewalQuoteId\":\""+staleId+"\"}"))
            .andExpect(status().isConflict());
        try(var connection=datasource.getConnection();var statement=connection.prepareStatement("select count(*) from renewals")){
            try(var rows=statement.executeQuery()){rows.next();assertThat(rows.getLong(1)).isZero();}
        }
        var fresh=mvc.perform(post(path+"/renewal-quote").contentType("application/json").content("{\"pricingPackageCode\":\"TEST\"}"))
            .andExpect(status().isOk()).andReturn();
        String freshId=new ObjectMapper().readTree(fresh.getResponse().getContentAsString()).path("data").path("id").asText();
        var submitted=mvc.perform(post(path+"/renewal-requests").header("Idempotency-Key","fresh-policy").contentType("application/json")
            .content("{\"renewalQuoteId\":\""+freshId+"\"}"))
            .andExpect(status().isCreated()).andReturn();
        String requestId=new ObjectMapper().readTree(submitted.getResponse().getContentAsString()).path("data").path("id").asText();
        try(var connection=datasource.getConnection();var statement=connection.prepareStatement("select count(*) from renewals")){
            try(var rows=statement.executeQuery()){rows.next();assertThat(rows.getLong(1)).isEqualTo(1);}
        }
        assertThat(reads.detail(a,UUID.fromString(requestId),false).acceptedQuoteId()).isEqualTo(UUID.fromString(freshId));
    }
    @Test void acceptedCancellationReleasesSlotKeepsHistoryAndReplays(){
        var r=fixture();var a=customer(r);var q=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));
        var n=commands.submit(a,r.getId(),new RenewalCommands.Submit(q.id(),null),"submit");
        var body=new RenewalCommands.Cancel("test cancel",n.version());var cancelled=commands.cancel(a,n.id(),body,"cancel");
        assertThat(cancelled.status()).isEqualTo(RenewalStatus.cancelled);assertThat(commands.cancel(a,n.id(),body,"cancel")).isEqualTo(cancelled);
        assertThat(cancelled.reviewerId()).isNull();assertThat(cancelled.reviewedAt()).isNull();assertThat(em.find(RenewalWorkflow.class,n.id()).getCancellationReason()).isEqualTo("test cancel");
        em.flush();em.clear();assertThat(reads.detail(a,n.id(),false).cancellationReason()).isEqualTo("test cancel");
        assertThat(em.find(RenewalOpenSlot.class,r.getId())).isNull();assertThat(em.find(Renewal.class,n.id())).isNotNull();assertThat(hold.calls).isZero();
    }
    @Test void financialReadbackIsPartialWhenConsistencyIsUnavailable(){
        var r=fixture();var a=customer(r);var q=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));
        var n=commands.submit(a,r.getId(),new RenewalCommands.Submit(q.id(),null),"submit-financial-partial");
        finance.consistent=false;
        var managerView=reads.detail(manager(r),n.id(),true);
        assertThat(managerView.financialCheck().completeness()).isEqualTo("PARTIAL");
        assertThat(managerView.allowedActions()).doesNotContain("APPROVE");
        assertThat(hold.calls).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"RETURNED","MAINTENANCE","RECOVERY","BOOKING"})
    void operationalConflictIsRecheckedByOptionsQuoteSubmitAndEdit(String kind){
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();
        factory.addBean("guard",new com.storagehub.service.renewal.integration.RenewalOperationalGuard(em));
        org.springframework.test.util.ReflectionTestUtils.setField(commands,"operationalGuards",factory.getBeanProvider(com.storagehub.service.renewal.integration.RenewalOperationalGuard.class));
        try {
            var r=fixture();r.getStorageUnit().setStatus(StorageUnitStatus.occupied);em.flush();
            var a=customer(r);var q=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));
            var n=commands.submit(a,r.getId(),new RenewalCommands.Submit(q.id(),null),"before-conflict");
            var q2=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));
            switch(kind){
                case "RETURNED" -> r.setActualReturnedAt(NOW);
                case "MAINTENANCE" -> {var task=new MaintenanceTask();task.setFacility(r.getFacility());task.setStorageUnit(r.getStorageUnit());task.setTitle("TEST");task.setReason("TEST");task.setReportedBy(r.getCustomer());em.persist(task);}
                case "RECOVERY" -> em.persist(new com.storagehub.service.overdue.OverdueFollowUp("RENTAL_TERM:"+r.getId(),r,"RECOVERY_HANDOFF","TEST",r.getCustomer().getId(),NOW,UUID.randomUUID(),"TEST","1"));
                case "BOOKING" -> {var other=new Reservation();other.setReservationCode("OTHER");other.setIdempotencyKey("OTHER");other.setCustomer(r.getCustomer());other.setSourceQuote(r.getReservation().getSourceQuote());other.setFacility(r.getFacility());other.setUnitType(r.getStorageUnit().getUnitType());other.setAssignedUnit(r.getStorageUnit());other.setStartDate(r.getStartDate());other.setEndDate(r.getContractEndDate());other.setStatus(ReservationStatus.CONFIRMED);em.persist(other);}
            }
            em.flush();
            String expected=switch(kind){case "RETURNED" -> "non-active rental";case "MAINTENANCE" -> "Active maintenance";case "RECOVERY" -> "Recovery handoff";default -> "Another booking";};
            assertThatThrownBy(()->commands.options(a,r.getId())).hasMessageContaining(expected);
            assertThatThrownBy(()->commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"))).hasMessageContaining(expected);
            assertThatThrownBy(()->commands.submit(a,r.getId(),new RenewalCommands.Submit(q2.id(),null),"after-conflict")).hasMessageContaining(expected);
            assertThatThrownBy(()->commands.edit(a,n.id(),new RenewalCommands.Edit(q2.id(),null,n.version()),"edit-conflict")).hasMessageContaining(expected);
            assertThat(hold.calls).isZero();assertThat(em.find(Renewal.class,n.id()).getStatus()).isEqualTo(RenewalStatus.accepted);
            assertThat(reads.detail(manager(r),n.id(),true).allowedActions()).doesNotContain("APPROVE");
            // Successful replay/cancel/reject remain usable; a new conflict must not duplicate the request.
            assertThat(commands.submit(a,r.getId(),new RenewalCommands.Submit(q.id(),null),"before-conflict").id()).isEqualTo(n.id());
            assertThat(commands.cancel(a,n.id(),new RenewalCommands.Cancel("TEST",n.version()),"cancel-conflict").status()).isEqualTo(RenewalStatus.cancelled);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(commands,"operationalGuards",new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(com.storagehub.service.renewal.integration.RenewalOperationalGuard.class));
        }
    }
    private ActorPrincipal customer(Rental r){return new ActorPrincipal(r.getCustomer().getId(),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of());}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"MATCH","REFERENCE","VERSION","CUTOFF","MISSING_POLICY","EXPIRED_POLICY","RECOVERY","UNKNOWN_CALENDAR"})
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void publishedPolicyAndBoundEligibilityGateHttpQuoteWithCommittedReadback(String kind) throws Exception {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var mapper=new ObjectMapper().findAndRegisterModules();
        var r=tx.execute(s->{var rental=fixture();rental.getStorageUnit().setStatus(StorageUnitStatus.occupied);em.flush();return rental;});
        if(!kind.equals("MISSING_POLICY"))tx.executeWithoutResult(s->{
            var setting=new SystemSetting();setting.setSettingKey(com.storagehub.service.renewal.integration.RenewalPolicyPublicationService.key(r.getFacility().getId()));
            setting.setGroupName("TEST");setting.setLabel("TEST");setting.setSettingType("text");
            var policy=new PublishedRenewalPolicy("RENEWAL_POLICY_V1",r.getFacility().getId(),1,"test-published-v1",r.getCustomer().getId(),NOW.minusSeconds(1),
                LocalDate.of(2026,10,1),kind.equals("EXPIRED_POLICY")?LocalDate.of(2026,10,5):LocalDate.of(2026,11,30),30,24,30,new BigDecimal("0.2"),Set.of(PACKAGE),
                new PublishedRenewalPolicy.Signing(60,60),new PublishedRenewalPolicy.Term("CALENDAR_DAYS","Asia/Ho_Chi_Minh",1,3,7,8,LocalTime.of(17,0),LocalTime.of(8,0)));
            try{setting.setValue(mapper.writeValueAsString(policy));}catch(Exception e){throw new IllegalStateException(e);}em.persist(setting);
        });
        var periods=(com.storagehub.service.rental.period.RentalPeriodResolver)org.springframework.test.util.ReflectionTestUtils.getField(commands,"periods");
        var published=new com.storagehub.service.renewal.integration.PublishedRenewalPolicyAdapters(
            new com.storagehub.service.renewal.integration.RenewalPolicyPublicationService(em,mapper,null,null),periods,em,mapper,null);
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();
        factory.addBean("policy",(RenewalSources.PolicySource)(rental,start)->published.read(rental,start));
        var termCalls=new java.util.concurrent.atomic.AtomicInteger();
        factory.addBean("term",(com.storagehub.service.overdue.OverdueSources.TermSource)(rental,at)->published.term(rental,at).map(t->{
            if(termCalls.incrementAndGet()!=2)return t;
            return new com.storagehub.service.overdue.OverdueSources.Term(kind.equals("REFERENCE")?"OTHER":t.policyRef(),kind.equals("VERSION")?"v2":t.policyVersion(),
                t.lastPermittedDate(),t.warningThroughDay(),t.seriousThroughDay(),t.urgentThroughDay(),t.recoveryFromDay(),
                kind.equals("CUTOFF")?t.recoveryCutoff().minusSeconds(1):t.recoveryCutoff(),t.recoveryStart());
        }));
        // External receiver/calendar are TEST-ONLY boundary fixtures; published policy and DB services are real.
        factory.addBean("recovery",new com.storagehub.service.overdue.OverdueSources.RecoverySource(){
            public com.storagehub.service.overdue.OverdueSources.Handoff receive(Rental rental,String ref,String reason,String key,Instant at){throw new UnsupportedOperationException();}
            public Optional<com.storagehub.service.overdue.OverdueSources.RecoveryState> state(Rental rental,Instant at){return Optional.of(new com.storagehub.service.overdue.OverdueSources.RecoveryState(rental.getId(),rental.getFacility().getId(),at,kind.equals("RECOVERY")));}
        });
        var calendar=mock(com.storagehub.service.renewal.operations.RenewalOperationSources.CalendarSource.class);
        when(calendar.atomic()).thenReturn(true);when(calendar.feasible(any(Rental.class),any(),any())).thenReturn(kind.equals("UNKNOWN_CALENDAR")?Optional.empty():Optional.of(true));factory.addBean("calendar",calendar);
        var coverage=new com.storagehub.service.renewal.integration.SharedRenewalEligibilityEvidenceSource(factory.getBeanProvider(com.storagehub.service.overdue.OverdueSources.RecoverySource.class),factory.getBeanProvider(com.storagehub.service.renewal.operations.RenewalOperationSources.CalendarSource.class),factory.getBeanProvider(com.storagehub.service.overdue.OverdueSources.TermSource.class));
        factory.addBean("coverage",coverage);
        factory.addBean("eligibility",new com.storagehub.service.renewal.integration.SharedRenewalEligibilitySource(new com.storagehub.service.renewal.integration.RenewalOperationalGuard(em),periods,factory.getBeanProvider(com.storagehub.service.overdue.OverdueSources.TermSource.class),factory.getBeanProvider(com.storagehub.service.renewal.integration.RenewalEligibilityEvidenceSource.class)));
        var oldPolicy=org.springframework.test.util.ReflectionTestUtils.getField(commands,"policies");var oldEligibility=org.springframework.test.util.ReflectionTestUtils.getField(commands,"eligibility");
        try{
            org.springframework.test.util.ReflectionTestUtils.setField(commands,"policies",factory.getBeanProvider(RenewalSources.PolicySource.class));
            org.springframework.test.util.ReflectionTestUtils.setField(commands,"eligibility",factory.getBeanProvider(RenewalSources.EligibilitySource.class));
            var context=mock(ActorContext.class);when(context.required()).thenReturn(customer(r));
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new CustomerRenewalController(context,reads,commands))
                .setControllerAdvice(new RenewalRequestAdvice(),new com.storagehub.common.api.GlobalExceptionHandler()).build();
            var result=mvc.perform(post("/api/customer/rentals/"+r.getId()+"/renewal-quote").contentType("application/json").content("{\"pricingPackageCode\":\"TEST\"}"));
            if(kind.equals("MATCH"))result.andExpect(status().isOk()).andExpect(jsonPath("data.renewalPolicyVersion").value("test-published-v1"));
            else result.andExpect(status().isConflict());
            tx.executeWithoutResult(s->{em.clear();assertThat(em.find(Rental.class,r.getId()).getContractEndDate()).isEqualTo(r.getContractEndDate());
                var rows=em.createQuery("select q from RenewalQuote q where q.rental.id=:id",RenewalQuote.class).setParameter("id",r.getId()).getResultList();
                assertThat(rows).hasSize(kind.equals("MATCH")?1:0);
                if(!rows.isEmpty())assertThat(rows.getFirst().getTermsJson()).contains("test-published-v1","system-setting:");
            });
            try(var connection=datasource.getConnection();var statement=connection.prepareStatement("select terms_json from renewal_quotes where rental_id=?")){
                var uuid=r.getId();statement.setBytes(1,java.nio.ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array());
                try(var rows=statement.executeQuery()){
                    if(kind.equals("MATCH")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).contains("test-published-v1","system-setting:");}
                    assertThat(rows.next()).isFalse();
                }
            }
        }finally{
            org.springframework.test.util.ReflectionTestUtils.setField(commands,"policies",oldPolicy);org.springframework.test.util.ReflectionTestUtils.setField(commands,"eligibility",oldEligibility);
        }
    }
    private ActorPrincipal manager(Rental r){return new ActorPrincipal(r.getCustomer().getId(),UUID.randomUUID(),Set.of(RoleCode.MANAGER),Set.of(SystemPermission.VIEW_RENTALS.code(),SystemPermission.MANAGE_RENTALS.code()),Map.of(r.getFacility().getId(),FacilityScopeLevel.MANAGE));}
    @Test void simplePaymentRequiresSigningSlotButNotRecoveryCoverageOrExtensionHold() {
        var simple=mock(com.storagehub.service.renewal.integration.RenewalLocalPaymentChecks.class);
        var writer=mock(com.storagehub.service.ledger.RenewalDepositObligationWriter.class);
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();beans.addBean("simple",simple);beans.addBean("writer",writer);
        Object old=org.springframework.test.util.ReflectionTestUtils.getField(commands,"localPaymentChecks");
        Object oldWriter=org.springframework.test.util.ReflectionTestUtils.getField(commands,"depositWriters");
        Object oldCalendars=org.springframework.test.util.ReflectionTestUtils.getField(commands,"calendars");
        var calendar=mock(com.storagehub.service.renewal.operations.RenewalOperationSources.CalendarSource.class);
        beans.addBean("calendar",calendar);
        when(calendar.atomic()).thenReturn(true);
        var signingSlot=new com.storagehub.service.renewal.operations.RenewalOperationSources.Slot("TEST-SLOT",NOW.plusSeconds(3600),NOW.plusSeconds(5400));
        when(calendar.slot(any(),eq(signingSlot.start()))).thenReturn(Optional.of(signingSlot));
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(commands,"calendars",beans.getBeanProvider(com.storagehub.service.renewal.operations.RenewalOperationSources.CalendarSource.class));
            org.springframework.test.util.ReflectionTestUtils.setField(commands,"localPaymentChecks",beans.getBeanProvider(com.storagehub.service.renewal.integration.RenewalLocalPaymentChecks.class));
            org.springframework.test.util.ReflectionTestUtils.setField(commands,"depositWriters",beans.getBeanProvider(com.storagehub.service.ledger.RenewalDepositObligationWriter.class));
            var r=fixture();r.getStorageUnit().setStatus(StorageUnitStatus.occupied);em.flush();
            when(simple.cutoff(any(),any())).thenReturn(NOW.plusSeconds(86400));
            eligibility.unknown=true;finance.unknown=true;
            var actor=customer(r);var q=commands.quote(actor,r.getId(),new RenewalCommands.Quote("TEST"));
            var submitted=commands.submit(actor,r.getId(),new RenewalCommands.Submit(q.id(),null),"simple-submit");
            assertThatThrownBy(()->commands.prepareVnpayPayment(actor,submitted.id())).hasMessageContaining("Select a future signing appointment");
            var selected=new com.storagehub.service.renewal.operations.RenewalOperationState(em.find(Renewal.class,submitted.id()));
            selected.awaitingPayment(NOW.plusSeconds(900),NOW.plusSeconds(86400),"test-policy","v1");
            selected.appointment(UUID.randomUUID(),signingSlot.start(),signingSlot.end());em.persist(selected);em.flush();
            var result=commands.prepareVnpayPayment(actor,submitted.id());
            assertThat(result.workflow().getExtensionHoldRef()).isNull();assertThat(result.paymentDeadline()).isAfter(NOW);
            assertThat(result.terms().renewalDepositAmount()).isEqualByComparingTo("20");
            verify(calendar).reserve(any(),eq(signingSlot),eq(NOW));
            verify(simple).requireKnownFinance(any(),eq(submitted.id()),eq(NOW));
            verify(writer).paymentIntentAvailable(any(),any(),eq(actor.userId()));
            Instant original=result.paymentDeadline();
            assertThat(commands.prepareVnpayPayment(actor,submitted.id()).paymentDeadline()).isEqualTo(original);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(commands,"calendars",oldCalendars);
            org.springframework.test.util.ReflectionTestUtils.setField(commands,"localPaymentChecks",old);
            org.springframework.test.util.ReflectionTestUtils.setField(commands,"depositWriters",oldWriter);
        }
    }
    // Clearly labeled H2-only fixture; no runtime policy/data seeds.
    private Rental fixture(){
        String fixtureKey=UUID.randomUUID().toString().substring(0,8);
        Facility f=new Facility();f.setCode("TEST-"+fixtureKey);f.setName("Test");f.setAddress("Test");f.setCity("Test");em.persist(f);
        User u=new User();u.setEmail(fixtureKey+"@test.invalid");u.setFullName("Test Customer");u.setPasswordHash("test-only");em.persist(u);
        UnitType t=new UnitType();t.setFacility(f);t.setCode("M");t.setName("Medium");t.setLengthM(BigDecimal.ONE);t.setWidthM(BigDecimal.ONE);t.setHeightM(BigDecimal.ONE);t.setMonthlyPrice(new BigDecimal("100"));t.setMaxLoadKg(BigDecimal.ONE);t.setRackLengthM(BigDecimal.ONE);t.setRackWidthM(BigDecimal.ONE);t.setRackHeightM(BigDecimal.ONE);em.persist(t);
        StorageUnit unit=new StorageUnit();unit.setFacility(f);unit.setUnitType(t);unit.setCode("TEST-"+fixtureKey);em.persist(unit);
        ReservationQuote q=new ReservationQuote();q.setCustomer(u);q.setFacility(f);q.setUnitType(t);q.setPricingPackageCode("TEST");q.setPolicyVersion("test-only");q.setStartDate(LocalDate.of(2026,10,1));q.setEndDate(LocalDate.of(2026,11,1));q.setRentalMonths(1);q.setMonthlyPrice(new BigDecimal("100"));q.setSubtotal(new BigDecimal("100"));q.setDiscountRate(BigDecimal.ZERO);q.setDiscountAmount(BigDecimal.ZERO);q.setTotalAfterDiscount(new BigDecimal("100"));q.setReservationDepositAmount(BigDecimal.ZERO);q.setSecurityDepositAmount(BigDecimal.ZERO);q.setRemainingRentalAmount(new BigDecimal("100"));q.setDueAtCheckIn(new BigDecimal("100"));q.setTotalInitialObligation(new BigDecimal("100"));q.setQuotedAt(NOW);q.setExpiresAt(NOW.plusSeconds(1800));em.persist(q);
        Reservation res=new Reservation();res.setReservationCode("TEST-"+fixtureKey);res.setCustomer(u);res.setSourceQuote(q);res.setIdempotencyKey("TEST-"+fixtureKey);res.setFacility(f);res.setUnitType(t);res.setStartDate(q.getStartDate());res.setEndDate(q.getEndDate());em.persist(res);
        Rental r=new Rental();r.setCustomer(u);r.setFacility(f);r.setStorageUnit(unit);r.setReservation(res);r.setStartDate(q.getStartDate());r.setContractEndDate(LocalDate.of(2026,10,31));r.setMonthlyPrice(new BigDecimal("100"));em.persist(r);em.flush();return r;
    }
}
