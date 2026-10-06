package com.storagehub.service.renewal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.*;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.*;
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
@Import({RenewalWorkflowService.class,RenewalPersistence.class,RenewalReadService.class,AuditLogService.class,RenewalWorkflowIntegrationTests.Sources.class})
class RenewalWorkflowIntegrationTests {
    static final Instant NOW=Instant.parse("2026-10-06T00:00:00Z");
    static final UUID PACKAGE=UUID.fromString("00000000-0000-0000-0000-000000000001");
    @TestConfiguration static class Sources {
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean ActorContext actorContext(){return mock(ActorContext.class);}
        @Bean TestClock clock(){return new TestClock();}
        @Bean RenewalSources.PolicySource policy(){return (r,d)->Optional.of(new RenewalSources.Policy("test-policy","v1",Duration.ofMinutes(30),Duration.ofHours(24),30,new BigDecimal("0.2"),Set.of(PACKAGE)));}
        @Bean RenewalSources.EligibilitySource eligibility(){return (r,n)->Optional.of(new RenewalSources.Eligibility(true,false,true,NOW.plusSeconds(172800)));}
        @Bean TestPrice prices(){return new TestPrice();}
        @Bean TestFinance finance(){return new TestFinance();}
        @Bean TestHold hold(){return new TestHold();}
    }
    static class TestPrice implements RenewalSources.PricingSource {
        BigDecimal rate=new BigDecimal("100");
        public Optional<List<RenewalSources.Price>> read(Rental r,LocalDate start){return Optional.of(List.of(new RenewalSources.Price(PACKAGE,"TEST","v1",r.getStorageUnit().getUnitType().getId(),1,rate,BigDecimal.ZERO,"VND",2)));}
    }
    static class TestFinance implements RenewalSources.FinancialSource {
        boolean unknown=false;boolean debt=false;
        public Optional<RenewalSources.Financial> read(Rental r){return unknown?Optional.empty():Optional.of(new RenewalSources.Financial(NOW,debt?List.of(PACKAGE):List.of(),false));}
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
    @Autowired TestPrice pricing;@Autowired TestFinance finance;@Autowired TestHold hold;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired TestClock clock;
    @Test void httpCommandsExposeRealResultsAndRejectInjectedFields() throws Exception {
        var r=fixture();var a=customer(r);var context=mock(ActorContext.class);when(context.required()).thenReturn(a);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new CustomerRenewalController(context,reads,commands),new ManagerRenewalController(context,reads,commands))
            .setControllerAdvice(new RenewalRequestAdvice(),new com.storagehub.common.api.GlobalExceptionHandler()).build();
        String path="/api/customer/rentals/"+r.getId();
        mvc.perform(post(path+"/renewal-quote").contentType("application/json").content("{\"pricingPackageCode\":\"TEST\",\"amount\":1}")).andExpect(status().isBadRequest());
        var quote=mvc.perform(post(path+"/renewal-quote").contentType("application/json").content("{\"pricingPackageCode\":\"TEST\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("data.monthlyPrice").value(100)).andReturn();
        String quoteId=new ObjectMapper().readTree(quote.getResponse().getContentAsString()).path("data").path("id").asText();
        mvc.perform(post(path+"/renewal-requests").contentType("application/json").content("{\"renewalQuoteId\":\""+quoteId+"\"}")).andExpect(status().isBadRequest());
        String body="{\"renewalQuoteId\":\""+quoteId+"\"}";
        mvc.perform(post(path+"/renewal-requests").header("Idempotency-Key","http").contentType("application/json").content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("data.status").value("pending")).andExpect(jsonPath("data.version").value(0));
        mvc.perform(post(path+"/renewal-requests").header("Idempotency-Key","http").contentType("application/json").content(body)).andExpect(status().isCreated());
        assertThat(em.createQuery("select count(x) from Renewal x",Long.class).getSingleResult()).isEqualTo(1);
    }
    @BeforeEach void resetSources(){pricing.rate=new BigDecimal("100");finance.unknown=false;finance.debt=false;hold.calls=0;hold.atomic=true;hold.fail=false;clock.time=NOW;}
    @Test void acceptedPendingDoesNotExpireWithQuoteTtl(){
        var r=fixture();var a=customer(r);var q=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));var n=commands.submit(a,r.getId(),new RenewalCommands.Submit(q.id(),null),"submit");
        clock.time=NOW.plusSeconds(1801);
        assertThat(commands.decision(manager(r),n.id(),new RenewalCommands.Decision(RenewalCommands.DecisionType.APPROVE,null,n.version()),"approve").status()).isEqualTo(RenewalStatus.approved);
    }
    @Test void debtAllowsRequestButBlocksApproval(){
        var r=fixture();finance.debt=true;var a=customer(r);var q=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));var n=commands.submit(a,r.getId(),new RenewalCommands.Submit(q.id(),null),"submit");
        assertThat(n.financialCheck().blockingObligationRefs()).containsExactly(PACKAGE);
        assertThatThrownBy(()->commands.decision(manager(r),n.id(),new RenewalCommands.Decision(RenewalCommands.DecisionType.APPROVE,null,n.version()),"approve")).isInstanceOf(ApiException.class);assertThat(hold.calls).isZero();
    }
    @Test void moveOutRequestBlocksApproval(){
        var r=fixture();var a=customer(r);var q=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));var n=commands.submit(a,r.getId(),new RenewalCommands.Submit(q.id(),null),"submit");
        r.setStatus(RentalStatus.return_requested);em.flush();
        assertThatThrownBy(()->commands.decision(manager(r),n.id(),new RenewalCommands.Decision(RenewalCommands.DecisionType.APPROVE,null,n.version()),"approve")).isInstanceOf(ApiException.class);assertThat(hold.calls).isZero();
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
    @Test @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void holdFailureRollsBackDecisionMarkerAuditAndReplayRecord(){
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var r=tx.execute(s->fixture());var a=customer(r);var manager=manager(r);
        var q=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));var n=commands.submit(a,r.getId(),new RenewalCommands.Submit(q.id(),null),"submit");
        hold.fail=true;
        assertThatThrownBy(()->commands.decision(manager,n.id(),new RenewalCommands.Decision(RenewalCommands.DecisionType.APPROVE,null,n.version()),"approve")).isInstanceOf(IllegalStateException.class);
        tx.executeWithoutResult(s->{
            assertThat(em.find(Renewal.class,n.id()).getStatus()).isEqualTo(RenewalStatus.pending);
            assertThat(em.find(RenewalWorkflow.class,n.id()).getExtensionHoldRef()).isNull();
            assertThat(em.createQuery("select count(x) from ActivityLog x where x.entityType='TestHold'",Long.class).getSingleResult()).isZero();
            assertThat(em.createQuery("select count(x) from RenewalIdempotency x where x.operation='decision'",Long.class).getSingleResult()).isZero();
        });
    }
    @Test void submitReplayApproveReplayAuditAndNoEndExtension(){
        var r=fixture();var customer=customer(r);var manager=manager(r);var oldEnd=r.getContractEndDate();
        var quote=commands.quote(customer,r.getId(),new RenewalCommands.Quote("TEST"));
        var body=new RenewalCommands.Submit(quote.id(),"test");var created=commands.submit(customer,r.getId(),body,"submit");
        assertThat(created.version()).isZero();assertThat(created.acceptedRevision()).isEqualTo(1);assertThat(created.reviewState()).isEqualTo("READY");
        assertThat(commands.submit(customer,r.getId(),body,"submit")).isEqualTo(created);
        var decision=new RenewalCommands.Decision(RenewalCommands.DecisionType.APPROVE,null,created.version());
        var approved=commands.decision(manager,created.id(),decision,"approve");
        assertThat(approved.status()).isEqualTo(RenewalStatus.approved);assertThat(approved.approvedPaymentDeadline()).isEqualTo(NOW.plusSeconds(86400));
        assertThat(commands.decision(manager,created.id(),decision,"approve")).isEqualTo(approved);assertThat(hold.calls).isEqualTo(1);
        assertThat(em.find(Rental.class,r.getId()).getContractEndDate()).isEqualTo(oldEnd);
        assertThat(em.createQuery("select count(x) from ActivityLog x where x.entityType='Renewal'",Long.class).getSingleResult()).isEqualTo(2);
        assertThat(reads.detail(customer,created.id(),false).acceptedQuoteId()).isEqualTo(quote.id());
    }
    @Test void requoteConsentRevisionsThenApproval(){
        var r=fixture();var customer=customer(r);var q=commands.quote(customer,r.getId(),new RenewalCommands.Quote("TEST"));
        var n=commands.submit(customer,r.getId(),new RenewalCommands.Submit(q.id(),null),"submit");pricing.rate=new BigDecimal("200");
        assertThat(reads.detail(customer,n.id(),false).reviewState()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        var q2=commands.quote(customer,r.getId(),new RenewalCommands.Quote("TEST"));
        var updated=commands.edit(customer,n.id(),new RenewalCommands.Edit(q2.id(),"consent",n.version()),"edit");
        assertThat(updated.acceptedRevision()).isEqualTo(2);assertThat(updated.amount()).isEqualByComparingTo("200");
        assertThat(commands.decision(manager(r),n.id(),new RenewalCommands.Decision(RenewalCommands.DecisionType.APPROVE,null,updated.version()),"approve").status()).isEqualTo(RenewalStatus.approved);
        assertThat(em.createQuery("select count(x) from RenewalAcceptedRevision x",Long.class).getSingleResult()).isEqualTo(2);
    }
    @Test void pendingCancellationReleasesSlotKeepsHistoryAndReplays(){
        var r=fixture();var a=customer(r);var q=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));
        var n=commands.submit(a,r.getId(),new RenewalCommands.Submit(q.id(),null),"submit");
        var body=new RenewalCommands.Cancel("test cancel",n.version());var cancelled=commands.cancel(a,n.id(),body,"cancel");
        assertThat(cancelled.status()).isEqualTo(RenewalStatus.cancelled);assertThat(commands.cancel(a,n.id(),body,"cancel")).isEqualTo(cancelled);
        assertThat(cancelled.reviewerId()).isNull();assertThat(cancelled.reviewedAt()).isNull();assertThat(em.find(RenewalWorkflow.class,n.id()).getCancellationReason()).isEqualTo("test cancel");
        assertThat(em.find(RenewalOpenSlot.class,r.getId())).isNull();assertThat(em.find(Renewal.class,n.id())).isNotNull();assertThat(hold.calls).isZero();
    }
    @Test void rejectPersistsActorReasonAndNoHold(){
        var r=fixture();var a=customer(r);var q=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));
        var n=commands.submit(a,r.getId(),new RenewalCommands.Submit(q.id(),null),"submit");
        var rejected=commands.decision(manager(r),n.id(),new RenewalCommands.Decision(RenewalCommands.DecisionType.REJECT,"test reason",n.version()),"reject");
        assertThat(rejected.status()).isEqualTo(RenewalStatus.rejected);assertThat(rejected.reviewReason()).isEqualTo("test reason");assertThat(rejected.reviewerId()).isEqualTo(r.getCustomer().getId());assertThat(hold.calls).isZero();
    }
    @Test void decisionUsesManagePermissionWithoutRequiringViewPermission(){
        var r=fixture();var a=customer(r);var q=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));var n=commands.submit(a,r.getId(),new RenewalCommands.Submit(q.id(),null),"submit");
        var manager=new ActorPrincipal(a.userId(),a.sessionId(),Set.of(RoleCode.MANAGER),Set.of(SystemPermission.MANAGE_RENTALS.code()),Map.of(r.getFacility().getId(),FacilityScopeLevel.MANAGE));
        assertThat(commands.decision(manager,n.id(),new RenewalCommands.Decision(RenewalCommands.DecisionType.REJECT,"test",n.version()),"reject").status()).isEqualTo(RenewalStatus.rejected);
    }
    @Test void financialUnknownBlocksApprovalBeforeHold(){
        var r=fixture();var a=customer(r);var q=commands.quote(a,r.getId(),new RenewalCommands.Quote("TEST"));var n=commands.submit(a,r.getId(),new RenewalCommands.Submit(q.id(),null),"submit");finance.unknown=true;
        assertThatThrownBy(()->commands.decision(manager(r),n.id(),new RenewalCommands.Decision(RenewalCommands.DecisionType.APPROVE,null,n.version()),"approve")).isInstanceOf(ApiException.class);assertThat(hold.calls).isZero();
    }
    private ActorPrincipal customer(Rental r){return new ActorPrincipal(r.getCustomer().getId(),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of());}
    private ActorPrincipal manager(Rental r){return new ActorPrincipal(r.getCustomer().getId(),UUID.randomUUID(),Set.of(RoleCode.MANAGER),Set.of(SystemPermission.VIEW_RENTALS.code(),SystemPermission.MANAGE_RENTALS.code()),Map.of(r.getFacility().getId(),FacilityScopeLevel.MANAGE));}
    // Clearly labeled H2-only fixture; no runtime policy/data seeds.
    private Rental fixture(){
        Facility f=new Facility();f.setCode("TEST");f.setName("Test");f.setAddress("Test");f.setCity("Test");em.persist(f);
        User u=new User();u.setEmail("fixture@test.invalid");u.setFullName("Test Customer");u.setPasswordHash("test-only");em.persist(u);
        UnitType t=new UnitType();t.setFacility(f);t.setCode("M");t.setName("Medium");t.setLengthM(BigDecimal.ONE);t.setWidthM(BigDecimal.ONE);t.setHeightM(BigDecimal.ONE);t.setMonthlyPrice(new BigDecimal("100"));t.setMaxLoadKg(BigDecimal.ONE);t.setRackLengthM(BigDecimal.ONE);t.setRackWidthM(BigDecimal.ONE);t.setRackHeightM(BigDecimal.ONE);em.persist(t);
        StorageUnit unit=new StorageUnit();unit.setFacility(f);unit.setUnitType(t);unit.setCode("TEST-1");em.persist(unit);
        ReservationQuote q=new ReservationQuote();q.setCustomer(u);q.setFacility(f);q.setUnitType(t);q.setPricingPackageCode("TEST");q.setPolicyVersion("test-only");q.setStartDate(LocalDate.of(2026,10,1));q.setEndDate(LocalDate.of(2026,11,1));q.setRentalMonths(1);q.setMonthlyPrice(new BigDecimal("100"));q.setSubtotal(new BigDecimal("100"));q.setDiscountRate(BigDecimal.ZERO);q.setDiscountAmount(BigDecimal.ZERO);q.setTotalAfterDiscount(new BigDecimal("100"));q.setReservationDepositAmount(BigDecimal.ZERO);q.setSecurityDepositAmount(BigDecimal.ZERO);q.setRemainingRentalAmount(new BigDecimal("100"));q.setDueAtCheckIn(new BigDecimal("100"));q.setTotalInitialObligation(new BigDecimal("100"));q.setQuotedAt(NOW);q.setExpiresAt(NOW.plusSeconds(1800));em.persist(q);
        Reservation res=new Reservation();res.setReservationCode("TEST");res.setCustomer(u);res.setSourceQuote(q);res.setIdempotencyKey("TEST");res.setFacility(f);res.setUnitType(t);res.setStartDate(q.getStartDate());res.setEndDate(q.getEndDate());em.persist(res);
        Rental r=new Rental();r.setCustomer(u);r.setFacility(f);r.setStorageUnit(unit);r.setReservation(res);r.setStartDate(q.getStartDate());r.setContractEndDate(LocalDate.of(2026,10,31));r.setMonthlyPrice(new BigDecimal("100"));em.persist(r);em.flush();return r;
    }
}
