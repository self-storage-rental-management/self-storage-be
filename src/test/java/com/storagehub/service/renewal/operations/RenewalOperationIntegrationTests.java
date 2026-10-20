package com.storagehub.service.renewal.operations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.*;
import com.storagehub.api.renewal.RenewalOperationCommands.*;
import com.storagehub.api.overdue.*;
import com.storagehub.common.api.*;
import com.storagehub.domain.model.*;
import com.storagehub.security.*;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.renewal.persistence.*;
import com.storagehub.service.overdue.*;
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
import org.springframework.util.LinkedMultiValueMap;

/** H2-only fixtures and explicitly TEST-ONLY integration adapters; never runtime policy/payment seeds. */
@DataJpaTest @ActiveProfiles("test")
@Import({com.storagehub.testsupport.DisplayCodeTestConfiguration.class,RenewalOperationService.class,OverdueService.class,RenewalPersistence.class,AuditLogService.class,RenewalOperationIntegrationTests.Sources.class})
class RenewalOperationIntegrationTests {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.storagehub.service.NotificationService workflowNotifications;
    static final Instant NOW=Instant.parse("2026-10-08T02:00:00Z");
    @TestConfiguration static class Sources {
        @Bean TestDateEvidence dateEvidence(){return new TestDateEvidence();}
        @Bean com.storagehub.service.rental.period.RentalPeriodResolver periods(org.springframework.beans.factory.ObjectProvider<com.storagehub.service.RentalReadSources.DateSource> sources){return new com.storagehub.service.rental.period.RentalPeriodResolver(sources);}
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean ActorContext actors(){return mock(ActorContext.class);}
        @Bean TestClock clock(){return new TestClock();}
        @Bean TestOps ops(TestClock clock){return new TestOps(clock);}
        @Bean TestOverdue overdue(TestClock clock){return new TestOverdue(clock);}
    }
    static class TestDateEvidence implements com.storagehub.service.RentalReadSources.DateSource {
        final Set<UUID> exclusive=java.util.concurrent.ConcurrentHashMap.newKeySet();
        public Optional<com.storagehub.service.RentalReadSources.Dates> read(Rental r){
            boolean e=exclusive.contains(r.getId());
            return Optional.of(new com.storagehub.service.RentalReadSources.Dates(r.getId(),r.getStartDate(),e?r.getContractEndDate().minusDays(1):r.getContractEndDate(),"TEST-ONLY-period",r.getContractEndDate(),e?com.storagehub.service.rental.period.RentalPeriod.Convention.EXCLUSIVE:com.storagehub.service.rental.period.RentalPeriod.Convention.INCLUSIVE));
        }
    }
    static class TestClock extends Clock {
        Instant time=NOW;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return Clock.fixed(time,z);}public Instant instant(){return time;}
    }
    static class TestOps implements RenewalOperationSources.AuthorizationSource,RenewalOperationSources.PolicySource,RenewalOperationSources.CalendarSource,RenewalOperationSources.SafetySource,RenewalOperationSources.AccountingSource,RenewalOperationSources.EvidenceSource,RenewalOperationSources.RefundSource {
        @Autowired EntityManager em;
        final TestClock clock;boolean atomic=true,authorized=true,fullyPaid=true,policyKnown=true,slotKnown=true,unsafeEvidence=false,failConsume=false,advanceDeposit=false,advanceCompletion=false;int deposits,cashes,consumes,releases;RenewalOperationSources.Outcome outcome=RenewalOperationSources.Outcome.SUCCESS;
        TestOps(TestClock clock){this.clock=clock;}
        public boolean atomic(){return atomic;}
        public void require(ActorPrincipal actor,Renewal n,RenewalOperationSources.Capability cap){if(!authorized)throw ApiExceptions.forbidden("TEST current assignment revoked");}
        Set<UUID> assignedIds=Set.of();public Set<UUID> assignedRenewalIds(ActorPrincipal actor){return assignedIds;}
        public Optional<RenewalOperationSources.Policy> read(Renewal n){return policyKnown?Optional.of(new RenewalOperationSources.Policy("TEST-BO","v1",Duration.ofHours(72),Duration.ofHours(24))):Optional.empty();}
        public void requireFacilityFault(Renewal n,UUID incident,UUID actor){}
        public Optional<RenewalOperationSources.Slot> slot(Renewal n,Instant start){return slotKnown?Optional.of(new RenewalOperationSources.Slot("TEST-SLOT",start,start.plusSeconds(3600))):Optional.empty();}
        public boolean feasible(Renewal n,Instant start,Instant deadline){return slotKnown;}
        boolean slowReservation;
        public void reserve(Renewal n,RenewalOperationSources.Slot slot,Instant now){if(slowReservation){var marker=new ActivityLog();marker.setAction("TEST_CALENDAR_RESERVE");marker.setEntityType("TestCalendarReservation");marker.setEntityId(n.getId());marker.setCorrelationId("test-calendar-rollback");em.persist(marker);em.flush();clock.time=slot.start().plusSeconds(1);}}
        public Instant check(Renewal n,RenewalQuoteResponse.Terms terms,UUID hold,Instant now){if(hold==null)throw ApiExceptions.conflict("TEST missing hold");return NOW.plus(Duration.ofDays(6));}
        public void retain(Renewal n,UUID hold,Instant deadline){}
        public void consume(Renewal n,UUID hold){consumes++;if(failConsume){var marker=new ActivityLog();marker.setAction("TEST_ROLLBACK_HOLD");marker.setEntityType("TestD3Hold");marker.setEntityId(n.getId());marker.setCorrelationId("test-only-rollback");em.persist(marker);em.flush();throw new IllegalStateException("TEST hold consume failed");}}
        public void release(Renewal n,UUID hold){releases++;}
        public RenewalOperationSources.Deposit deposit(Renewal n,RenewalQuoteResponse.Terms t,UUID actor,String key,Instant deadline){deposits++;if(advanceDeposit)clock.time=clock.time.plusSeconds(1);return new RenewalOperationSources.Deposit(UUID.randomUUID(),n.getId(),outcome,t.renewalDepositAmount(),"VND",outcome==RenewalOperationSources.Outcome.SUCCESS?clock.time:null);}
        public boolean safeToExpire(Renewal n){return true;}
        int statementMode;
        public RenewalOperationSources.Statement statement(Renewal n,RenewalQuoteResponse.Terms t,Instant now){
            return switch(statementMode){
                case 1 -> null;
                case 2 -> new RenewalOperationSources.Statement(n.getId(),n.getId(),"TEST-v1",now,t.remainingRentalAmount(),"VND",List.of(n.getId()));
                case 3 -> new RenewalOperationSources.Statement(n.getId(),UUID.randomUUID(),"TEST-v1",now.plusSeconds(300),t.remainingRentalAmount(),"VND",List.of(n.getId()));
                case 4 -> new RenewalOperationSources.Statement(n.getId(),n.getId(),"",now.plusSeconds(300),t.remainingRentalAmount(),"VND",List.of(n.getId()));
                case 5 -> new RenewalOperationSources.Statement(n.getId(),n.getId(),"TEST-v1",now.plusSeconds(300),t.remainingRentalAmount(),"VND",List.of(n.getId(),n.getId()));
                case 6 -> new RenewalOperationSources.Statement(n.getId(),n.getId(),"TEST-v1",now.plusSeconds(300),new BigDecimal("1.001"),"VND",List.of(n.getId()));
                default -> new RenewalOperationSources.Statement(n.getId(),n.getId(),"TEST-v1",now.plusSeconds(300),t.remainingRentalAmount(),"VND",List.of(n.getId()));
            };
        }
        public RenewalOperationSources.Receipt cash(Renewal n,UUID statement,String receipt,UUID actor,String key,Instant now){cashes++;return new RenewalOperationSources.Receipt(UUID.randomUUID(),n.getId(),statement,new BigDecimal("80"),"VND",now);}
        public void requireFullyPaid(Renewal n,RenewalQuoteResponse.Terms t,Instant now){if(advanceCompletion)clock.time=NOW.plus(Duration.ofHours(72)).plusSeconds(1);if(!fullyPaid)throw ApiExceptions.conflict("TEST incomplete required payment");}
        public void require(ActorPrincipal a,Renewal n,String purpose,List<UUID> ids){if(unsafeEvidence)throw ApiExceptions.notFound("TEST inaccessible evidence");}
        public RenewalOperationSources.RefundReservation reserve(Renewal n,UUID incident,UUID actor,String key,Instant now){return new RenewalOperationSources.RefundReservation(UUID.randomUUID(),n.getId(),new BigDecimal("20"),"VND",now.plusSeconds(86400),now.plusSeconds(432000));}
    }
    static class TestOverdue implements OverdueSources.FinancialSource,OverdueSources.TermSource,OverdueSources.ReminderSource,OverdueSources.RecoverySource {
        final TestClock clock;boolean financeKnown=true,termKnown=true,atomic=true;int enqueues,handoffs;UUID obligation=UUID.randomUUID();
        TestOverdue(TestClock clock){this.clock=clock;}
        public boolean atomic(){return atomic;}
        public Optional<OverdueSources.Finance> read(Rental r,Instant now){return financeKnown?Optional.of(new OverdueSources.Finance(now,List.of(new OverdueSources.Obligation(obligation,r.getId(),NOW.minusSeconds(3600),new BigDecimal("5"),"VND"),new OverdueSources.Obligation(UUID.randomUUID(),r.getId(),NOW.plus(Duration.ofDays(40)),new BigDecimal("100"),"VND")))):Optional.empty();}
        public Optional<OverdueSources.Term> term(Rental r,Instant now){return termKnown?Optional.of(new OverdueSources.Term("TEST-TERM","v1",r.getContractEndDate(),3,6,7,8,r.getContractEndDate().plusDays(7).atTime(17,0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),r.getContractEndDate().plusDays(8).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant())):Optional.empty();}
        public Optional<OverdueSources.ReminderPolicy> policy(Rental r){return Optional.of(new OverdueSources.ReminderPolicy("TEST-REMINDER","v1",Duration.ofHours(24)));}
        public UUID enqueue(Rental r,String ref,String content,String key,Instant now){enqueues++;return UUID.randomUUID();}
        public OverdueSources.Handoff receive(Rental r,String ref,String reason,String key,Instant now){handoffs++;return new OverdueSources.Handoff(UUID.randomUUID(),"RECEIVED");}
    }
    @Autowired EntityManager em;@Autowired RenewalOperationService service;@Autowired OverdueService overdue;
    @Autowired RenewalPersistence store;@Autowired TestClock clock;@Autowired TestOps ops;@Autowired TestOverdue sources;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired TestDateEvidence dateEvidence;
    @Autowired javax.sql.DataSource datasource;
    @BeforeEach void reset(){clock.time=NOW;ops.slowReservation=false;ops.atomic=true;ops.authorized=true;ops.fullyPaid=true;ops.policyKnown=true;ops.slotKnown=true;ops.unsafeEvidence=false;ops.failConsume=false;ops.advanceDeposit=false;ops.advanceCompletion=false;ops.assignedIds=Set.of();ops.deposits=ops.cashes=ops.consumes=ops.releases=0;ops.outcome=RenewalOperationSources.Outcome.SUCCESS;sources.financeKnown=sources.termKnown=sources.atomic=true;sources.enqueues=sources.handoffs=0;}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void slowCalendarReservationRollsBackWithCommittedHttpReadback(boolean edit) throws Exception {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var n=tx.execute(s->edit?scheduled():paid());var customer=actor(n,RoleCode.CUSTOMER);
        var before=service.detail(customer,n.getId(),RenewalOperationService.Audience.CUSTOMER);
        var oldEnd=n.getRental().getContractEndDate();
        var actors=mock(ActorContext.class);when(actors.required()).thenReturn(customer);
        var http=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new CustomerRenewalOperationController(actors,service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        var path="/api/customer/renewals/"+n.getId()+"/appointment";
        var body=new Appointment(NOW.plusSeconds(10800),edit?"TEST reschedule":null,before.expectedVersion());
        ops.slowReservation=true;
        var request=edit?org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(path):org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path);
        http.perform(request.header("Idempotency-Key","slow-calendar").contentType("application/json").content(mapper().writeValueAsString(body)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("error.message").value("Appointment expired during reservation"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("data.expectedVersion").value(before.expectedVersion().intValue()));
        var after=service.detail(customer,n.getId(),RenewalOperationService.Audience.CUSTOMER);
        assertThat(after.appointmentRef()).isEqualTo(before.appointmentRef());assertThat(after.appointmentStart()).isEqualTo(before.appointmentStart());
        assertThat(after.appointmentEnd()).isEqualTo(before.appointmentEnd());assertThat(after.arrivalRef()).isEqualTo(before.arrivalRef());
        tx.executeWithoutResult(s->{
            assertThat(em.find(Rental.class,n.getRental().getId()).getContractEndDate()).isEqualTo(oldEnd);
            assertThat(em.createQuery("select count(e) from ActivityLog e where e.entityId=:id and e.entityType='TestCalendarReservation'",Long.class).setParameter("id",n.getId()).getSingleResult()).isZero();
            assertThat(em.createQuery("select count(e) from RenewalIdempotency e where e.resourceId=:id and e.operation=:op",Long.class).setParameter("id",n.getId()).setParameter("op",edit?"d3_reschedule":"d3_appointment").getSingleResult()).isZero();
        });
        try(var connection=datasource.getConnection();var query=connection.prepareStatement("select count(*) from renewal_operation_events where renewal_id=? and kind='APPOINTMENT'")){
            query.setBytes(1,java.nio.ByteBuffer.allocate(16).putLong(n.getId().getMostSignificantBits()).putLong(n.getId().getLeastSignificantBits()).array());
            try(var rows=query.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(edit?1:0);}
        }
        ops.slowReservation=false;
        // Failed key has no persisted command; a new valid slot can be submitted with it.
        var retry=new Appointment(clock.time.plusSeconds(3600),edit?"TEST retry":null,before.expectedVersion());
        var accepted=service.appointment(customer,n.getId(),retry,"slow-calendar",edit);
        assertThat(accepted.state().appointmentStart()).isEqualTo(retry.appointmentAt());
        assertThat(service.appointment(customer,n.getId(),retry,"slow-calendar",edit)).isEqualTo(accepted);
    }
    private ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path evidenceRoot;
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @Test @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void completionConsumesRealBoundSignedContractWithCommittedReadback() throws Exception {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var n=tx.execute(s->scheduled());
        var assigned=tx.execute(s->{
            var role=em.createQuery("select r from Role r where r.code=:code",com.storagehub.domain.model.Role.class).setParameter("code",RoleCode.STAFF).getResultStream().findFirst().orElse(null);
            if(role==null){role=new com.storagehub.domain.model.Role();role.setCode(RoleCode.STAFF);role.setName("TEST Staff");em.persist(role);}
            for(var code:List.of(SystemPermission.VIEW_RENTALS.code(),SystemPermission.MANAGE_RENTALS.code())){
                var permission=em.createQuery("select p from Permission p where p.code=:code",Permission.class).setParameter("code",code).getResultStream().findFirst().orElse(null);
                if(permission==null){permission=new Permission();permission.setCode(code);permission.setName(code);em.persist(permission);}role.getPermissions().add(permission);
            }
            var staff=new User();staff.setEmail(UUID.randomUUID()+"@test.invalid");staff.setFullName("TEST signing Staff");staff.setPasswordHash("TEST-ONLY");staff.setRoles(new HashSet<>(Set.of(role)));em.persist(staff);
            var current=em.find(Renewal.class,n.getId());
            var customerRole=em.createQuery("select r from Role r where r.code=:code",com.storagehub.domain.model.Role.class).setParameter("code",RoleCode.CUSTOMER).getResultStream().findFirst().orElse(null);
            if(customerRole==null){customerRole=new com.storagehub.domain.model.Role();customerRole.setCode(RoleCode.CUSTOMER);customerRole.setName("TEST Customer");em.persist(customerRole);}current.getRequestedBy().getRoles().add(customerRole);
            var scope=new UserFacilityScope();scope.setUser(staff);scope.setFacility(current.getRental().getFacility());scope.setScopeLevel(FacilityScopeLevel.OPERATE);em.persist(scope);
            var w=em.find(RenewalWorkflow.class,n.getId());
            var proof=new com.storagehub.service.renewal.integration.RenewalAssignmentSource.Assignment(n.getId(),current.getRental().getFacility().getId(),staff.getId(),w.getAcceptedRevision().getQuote().getId(),w.getVersion());
            em.persist(new RenewalOperationEvent(current,"STAFF_ASSIGNMENT",NOW,staff.getId(),store.canonical(proof)));em.flush();return staff.getId();
        });
        var staff=new ActorPrincipal(assigned,UUID.randomUUID(),Set.of(RoleCode.STAFF),Set.of(SystemPermission.VIEW_RENTALS.code(),SystemPermission.MANAGE_RENTALS.code()),Map.of(n.getRental().getFacility().getId(),FacilityScopeLevel.OPERATE));
        var state=service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF);
        var arrival=service.arrival(staff,n.getId(),new Arrival(state.appointmentRef(),List.of(),state.expectedVersion()),"real-file-arrival");
        var access=new com.storagehub.service.integration.DuongResourceAccess(em);
        var assignments=new com.storagehub.service.renewal.integration.RenewalAssignmentSource(em,mapper(),access);
        var properties=new com.storagehub.config.FileProperties();properties.setStoragePath(evidenceRoot.toString());
        var realEvidence=new com.storagehub.service.integration.DuongFileEvidence(em,access,assignments,properties,mapper());
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();beans.addBean("evidence",realEvidence);
        var original=org.springframework.test.util.ReflectionTestUtils.getField(service,"evidences");
        var originalAuthorization=org.springframework.test.util.ReflectionTestUtils.getField(service,"authorizations");
        beans.addBean("assignment",assignments);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"authorizations",beans.getBeanProvider(RenewalOperationSources.AuthorizationSource.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"evidences",beans.getBeanProvider(RenewalOperationSources.EvidenceSource.class));
        try {
            var actors=mock(ActorContext.class);when(actors.required()).thenReturn(staff);
            var http=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new StaffRenewalOperationController(actors,service)).setControllerAdvice(new GlobalExceptionHandler()).build();
            var path="/api/staff/renewals/"+n.getId();var oldEnd=n.getRental().getContractEndDate();
            for(var kind:List.of("INCIDENT","OTHER_RENEWAL","OTHER_UPLOADER","CHANGED_BYTES")){
                var id=persistSignedFile(tx,n.getId(),kind.equals("OTHER_UPLOADER")?n.getRequestedBy().getId():assigned,kind.equals("OTHER_RENEWAL")?UUID.randomUUID():n.getId(),kind.equals("INCIDENT")?"INCIDENT":"SIGNED_CONTRACT");
                if(kind.equals("CHANGED_BYTES")){var key=tx.execute(s->em.find(FileAsset.class,id).getStorageKey());java.nio.file.Files.writeString(java.nio.file.Path.of(key),"WRONG DOC");}
                var body=new Completion(arrival.state().expectedVersion(),true,arrival.state().arrivalRef(),id,"TEST invalid evidence");
                http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/completion").header("Idempotency-Key","invalid-"+kind).contentType("application/json").content(mapper().writeValueAsString(body)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(kind.equals("CHANGED_BYTES")?409:404));
                assertThat(service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF).expectedVersion()).isEqualTo(arrival.state().expectedVersion());
                tx.executeWithoutResult(s->assertThat(em.find(Rental.class,n.getRental().getId()).getContractEndDate()).isEqualTo(oldEnd));
            }
            var storage=new com.storagehub.service.FileStorageService(properties,
                applicationContext.getBean(com.storagehub.domain.repo.FileAssetRepository.class),applicationContext.getBean(com.storagehub.domain.repo.CheckInRepository.class),
                applicationContext.getBean(com.storagehub.domain.repo.ReservationGoodsItemRepository.class),applicationContext.getBean(com.storagehub.domain.repo.UserRepository.class),
                applicationContext.getBean(com.storagehub.domain.repo.PaymentComplaintRepository.class),applicationContext.getBean(com.storagehub.domain.repo.MaintenanceTaskRepository.class),
                mock(com.storagehub.service.AdminAuthorizationService.class),mock(com.storagehub.service.FacilityScopeService.class),mock(AuditLogService.class));
            org.springframework.test.util.ReflectionTestUtils.setField(storage,"duongEvidence",beans.getBeanProvider(com.storagehub.service.integration.DuongFileEvidence.class));
            var storageProxy=new org.springframework.aop.framework.ProxyFactory(storage);storageProxy.setProxyTargetClass(true);
            var attributes=new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();storageProxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,attributes));
            var fileHttp=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.storagehub.api.file.FileController(actors,(com.storagehub.service.FileStorageService)storageProxy.getProxy())).setControllerAdvice(new GlobalExceptionHandler()).build();
            byte[] uploadedBytes={(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a};
            var upload=fileHttp.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/files")
                .file(new org.springframework.mock.web.MockMultipartFile("file","signed.png","image/png",uploadedBytes)).param("entityType","DUONG_RENEWAL_SIGNED_CONTRACT").param("entityId",n.getId().toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("data.entityType").value("DUONG_RENEWAL_SIGNED_CONTRACT"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("data.entityId").value(n.getId().toString())).andReturn();
            var document=UUID.fromString(mapper().readTree(upload.getResponse().getContentAsString()).path("data").path("id").asText());
            var downloadPath="/api/files/"+document;
            when(actors.required()).thenReturn(actor(n,RoleCode.CUSTOMER));
            fileHttp.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(downloadPath)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            fileHttp.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/files").file(new org.springframework.mock.web.MockMultipartFile("file","signed.png","image/png",uploadedBytes)).param("entityType","DUONG_RENEWAL_SIGNED_CONTRACT").param("entityId",n.getId().toString())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            when(actors.required()).thenReturn(staff);
            var body=new Completion(arrival.state().expectedVersion(),true,arrival.state().arrivalRef(),document,"TEST signed contract");
            var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/completion").header("Idempotency-Key","bound-completion").contentType("application/json").content(mapper().writeValueAsString(body));
            http.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            http.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("data.phase").value("COMPLETED"));
            tx.executeWithoutResult(s->{assertThat(em.find(Rental.class,n.getRental().getId()).getContractEndDate()).isEqualTo(LocalDate.of(2026,11,6));realEvidence.requireDownload(actor(n,RoleCode.CUSTOMER),em.find(FileAsset.class,document));});
            when(actors.required()).thenReturn(actor(n,RoleCode.CUSTOMER));
            fileHttp.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(downloadPath))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(uploadedBytes))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Content-SHA256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(uploadedBytes))));
            when(actors.required()).thenReturn(staff);
            tx.executeWithoutResult(s->em.createQuery("delete from UserFacilityScope s where s.user.id=:id").setParameter("id",assigned).executeUpdate());
            http.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            try(var connection=datasource.getConnection();var query=connection.prepareStatement("select payload_json from renewal_operation_events where renewal_id=? and kind='COMPLETION'")){
                query.setBytes(1,java.nio.ByteBuffer.allocate(16).putLong(n.getId().getMostSignificantBits()).putLong(n.getId().getLeastSignificantBits()).array());
                try(var rows=query.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).contains(document.toString());assertThat(rows.next()).isFalse();}
            }
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(service,"evidences",original);org.springframework.test.util.ReflectionTestUtils.setField(service,"authorizations",originalAuthorization);}
    }
    private UUID persistSignedFile(org.springframework.transaction.support.TransactionTemplate tx,UUID renewal,UUID uploader,UUID resource,String purpose) throws Exception {
        var path=java.nio.file.Files.createTempFile(evidenceRoot,"signed-", ".txt");var bytes="TEST FILE".getBytes(java.nio.charset.StandardCharsets.UTF_8);java.nio.file.Files.write(path,bytes);
        var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        return tx.execute(s->{var file=new FileAsset();file.setUploadedBy(em.find(User.class,uploader));file.setStorageKey(path.toString());file.setOriginalName("test-signed.txt");file.setContentType("text/plain");file.setSizeBytes(bytes.length);file.setChecksumSha256(hash);file.setEntityType(com.storagehub.service.integration.DuongFileEvidence.renewalType(purpose));file.setEntityId(resource);em.persist(file);em.flush();return file.getId();});
    }
    @Test void missingAppointmentPeriodCannotRecordArrival() {
        var n=scheduled();var state=em.find(RenewalOperationState.class,n.getId());
        state.appointment(state.getAppointmentRef(),state.getAppointmentStart(),null);em.flush();
        assertThatThrownBy(()->service.arrival(actor(n,RoleCode.STAFF),n.getId(),new Arrival(state.getAppointmentRef(),List.of(),version(n)),"missing-period"))
            .hasMessageContaining("Appointment period unavailable");
        assertThat(state.getArrivalRef()).isNull();
    }
    @Test @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void expiredAppointmentRejectsArrivalWithCommittedReadback() throws Exception {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var n=tx.execute(s->scheduled());var staff=actor(n,RoleCode.STAFF);
        var before=service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF);
        clock.time=before.appointmentEnd().plusSeconds(1);
        assertThat(clock.time).isBefore(before.effectiveSigningDeadline());
        var actors=mock(ActorContext.class);when(actors.required()).thenReturn(staff);
        var http=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new StaffRenewalOperationController(actors,service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        var mapper=new ObjectMapper().findAndRegisterModules();
        var rejected=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/staff/renewals/"+n.getId()+"/arrival")
            .header("Idempotency-Key","expired-slot").contentType("application/json")
            .content(mapper.writeValueAsString(new Arrival(before.appointmentRef(),List.of(),before.expectedVersion())))).andReturn().getResponse();
        assertThat(rejected.getStatus()).isEqualTo(409);assertThat(rejected.getContentAsString()).contains("Appointment has expired");
        var read=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/staff/renewals/"+n.getId())).andReturn().getResponse();
        assertThat(read.getStatus()).isEqualTo(200);var data=mapper.readTree(read.getContentAsString()).path("data");
        assertThat(data.path("arrivalRef").isNull()).isTrue();assertThat(data.path("expectedVersion").asLong()).isEqualTo(before.expectedVersion());
        var after=service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF);
        assertThat(after.expectedVersion()).isEqualTo(before.expectedVersion());assertThat(after.arrivalRef()).isNull();
        assertArrivalRows(n.getId(),0);
    }
    @Test @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void arrivalAtSlotEndReplaysAfterExpiryWithoutDuplicateEvent() throws Exception {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var n=tx.execute(s->scheduled());var staff=actor(n,RoleCode.STAFF);
        var before=service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF);
        clock.time=before.appointmentEnd();var body=new Arrival(before.appointmentRef(),List.of(),before.expectedVersion());
        var accepted=service.arrival(staff,n.getId(),body,"slot-end-arrival");
        clock.time=before.effectiveSigningDeadline().plusSeconds(1);
        assertThat(service.arrival(staff,n.getId(),body,"slot-end-arrival")).isEqualTo(accepted);
        assertThat(service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF).arrivalRef()).isEqualTo(accepted.state().arrivalRef());
        assertArrivalRows(n.getId(),1);
    }
    private void assertArrivalRows(UUID renewalId,long expected) throws Exception {
        try(var connection=datasource.getConnection();var query=connection.prepareStatement("select count(*) from renewal_operation_events where renewal_id=? and kind='ARRIVAL'")) {
            query.setBytes(1,java.nio.ByteBuffer.allocate(16).putLong(renewalId.getMostSignificantBits()).putLong(renewalId.getLeastSignificantBits()).array());
            try(var rows=query.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(expected);}
        }
    }
    @Test void invalidStatementsNeverReachCashWriterOrChangeVersion(){
        var n=scheduled();var staff=actor(n,RoleCode.STAFF);var before=version(n);
        try{for(int mode=1;mode<=6;mode++){
            ops.statementMode=mode;
            String key="invalid-statement-"+mode;
            assertThatThrownBy(()->service.statement(staff,n.getId())).hasMessageContaining("Payable statement");
            assertThatThrownBy(()->service.cash(staff,n.getId(),new Cash(n.getId(),"TEST",true,before),key)).hasMessageContaining("Payable statement");
            assertThat(ops.cashes).isZero();assertThat(version(n)).isEqualTo(before);
        }}finally{ops.statementMode=0;}
    }
    @Test void availableLedgerMustMatchStatementRentalAndActualObligations(){
        var n=scheduled();var staff=actor(n,RoleCode.STAFF);
        var original=org.springframework.test.util.ReflectionTestUtils.getField(service,"ledgers");
        var provider=mock(org.springframework.beans.factory.ObjectProvider.class);
        var ledger=mock(com.storagehub.service.ledger.LedgerStore.class);when(provider.getIfAvailable()).thenReturn(ledger);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"ledgers",provider);
        try{
            when(ledger.read(n.getRental().getId())).thenReturn(Optional.empty());
            assertThatThrownBy(()->service.statement(staff,n.getId())).hasMessageContaining("ledger account missing");
            var r=n.getRental();
            when(ledger.read(r.getId())).thenReturn(Optional.of(new com.storagehub.service.ledger.LedgerStore.Snapshot(
                r.getId(),UUID.randomUUID(),r.getFacility().getId(),0,List.of(),List.of(),List.of())));
            assertThatThrownBy(()->service.statement(staff,n.getId())).hasMessageContaining("ledger binding invalid");
            when(ledger.read(r.getId())).thenReturn(Optional.of(new com.storagehub.service.ledger.LedgerStore.Snapshot(
                r.getId(),r.getCustomer().getId(),r.getFacility().getId(),0,List.of(),List.of(),List.of())));
            assertThatThrownBy(()->service.statement(staff,n.getId())).hasMessageContaining("obligation missing");
            assertThat(ops.cashes).isZero();
        }finally{org.springframework.test.util.ReflectionTestUtils.setField(service,"ledgers",original);}
    }
    @Test void slowFinancialVerificationCannotCompletePastDeadline(){var n=scheduled();var staff=actor(n,RoleCode.STAFF);var state=service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF);var arrival=service.arrival(staff,n.getId(),new Arrival(state.appointmentRef(),List.of(),version(n)),"arrival");ops.advanceCompletion=true;assertThatThrownBy(()->service.complete(staff,n.getId(),new Completion(version(n),true,arrival.state().arrivalRef(),UUID.randomUUID(),"TEST slow finance"),"slow")).hasMessageContaining("deadline");assertThat(n.getRental().getContractEndDate()).isEqualTo(LocalDate.of(2026,10,6));}
    @Test void httpRejectsMissingHeadersInjectedAmountsAndManagerCompletion() throws Exception {
        var n=fixture();var actors=mock(ActorContext.class);when(actors.required()).thenReturn(actor(n,RoleCode.CUSTOMER));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new CustomerRenewalOperationController(actors,service),new StaffRenewalOperationController(actors,service),new com.storagehub.api.overdue.ManagerOverdueController(actors,overdue)).setControllerAdvice(new RenewalOperationRequestAdvice(),new com.storagehub.api.overdue.OverdueRequestAdvice(),new GlobalExceptionHandler()).build();
        String path="/api/customer/renewals/"+n.getId()+"/appointment";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content("{\"expectedVersion\":"+version(n)+",\"appointmentAt\":\""+NOW.plusSeconds(3600)+"\"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key","bad").contentType("application/json").content("{\"expectedVersion\":"+version(n)+",\"appointmentAt\":\""+NOW.plusSeconds(3600)+"\",\"amount\":1}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        when(actors.required()).thenReturn(actor(n,RoleCode.MANAGER));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/staff/renewals/"+n.getId()+"/completion").header("Idempotency-Key","manager").contentType("application/json").content("{\"expectedVersion\":"+version(n)+",\"identityVerified\":true,\"arrivalRef\":\""+UUID.randomUUID()+"\",\"signedDocumentFileId\":\""+UUID.randomUUID()+"\"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        assertThat(ops.deposits).isZero();
    }
    @Test @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void concurrentCompletionsExtendOnce() throws Exception {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);var n=tx.execute(s->scheduled());var staff=actor(n,RoleCode.STAFF);
        var state=service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF);var arrival=service.arrival(staff,n.getId(),new Arrival(state.appointmentRef(),List.of(),state.expectedVersion()),"arrival");
        var body=new Completion(arrival.state().expectedVersion(),true,arrival.state().arrivalRef(),UUID.randomUUID(),"TEST race");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);try {var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();for(int i=0;i<2;i++){String key="complete-race-"+i;futures.add(pool.submit(()->{try{service.complete(staff,n.getId(),body,key);return true;}catch(ApiException e){return false;}}));}int wins=0;for(var f:futures)if(f.get(30,java.util.concurrent.TimeUnit.SECONDS))wins++;assertThat(wins).isEqualTo(1);
            tx.executeWithoutResult(s->{assertThat(em.find(Rental.class,n.getRental().getId()).getContractEndDate()).isEqualTo(n.getNewEndDate());assertThat(em.createQuery("select count(e) from RenewalOperationEvent e where e.kind='COMPLETION'",Long.class).getSingleResult()).isEqualTo(1);});
        } finally {pool.shutdownNow();}
    }
    @Test @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void sharedHoldFailureRollsBackCompletionAuditAndReplay() {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);var n=tx.execute(s->scheduled());var staff=actor(n,RoleCode.STAFF);var oldEnd=n.getRental().getContractEndDate();
        var state=service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF);var arrival=service.arrival(staff,n.getId(),new Arrival(state.appointmentRef(),List.of(),state.expectedVersion()),"arrival");ops.failConsume=true;
        assertThatThrownBy(()->service.complete(staff,n.getId(),new Completion(arrival.state().expectedVersion(),true,arrival.state().arrivalRef(),UUID.randomUUID(),"TEST rollback"),"rollback")).isInstanceOf(IllegalStateException.class);
        tx.executeWithoutResult(s->{assertThat(em.find(Rental.class,n.getRental().getId()).getContractEndDate()).isEqualTo(oldEnd);assertThat(em.find(Renewal.class,n.getId()).getStatus()).isEqualTo(RenewalStatus.appointment_scheduled);assertThat(em.createQuery("select count(e) from ActivityLog e where e.entityType='TestD3Hold'",Long.class).getSingleResult()).isZero();assertThat(em.createQuery("select count(e) from RenewalIdempotency e where e.operation='d3_complete' and e.resourceId=:id",Long.class).setParameter("id",n.getId()).getSingleResult()).isZero();});
    }
    @Test void customerCannotSeeOtherRentalAndManagerCannotComplete(){var n=fixture();var other=new ActorPrincipal(UUID.randomUUID(),UUID.randomUUID(),Set.of(RoleCode.CUSTOMER),Set.of(),Map.of());assertThatThrownBy(()->service.detail(other,n.getId(),RenewalOperationService.Audience.CUSTOMER)).hasMessageContaining("not found");assertThatThrownBy(()->service.complete(actor(n,RoleCode.MANAGER),n.getId(),new Completion(version(n),true,UUID.randomUUID(),UUID.randomUUID(),null),"manager")).hasMessageContaining("Staff role");}
    @Test void slotEndMustFitDeadline(){var n=paid();clock.time=NOW.plus(Duration.ofHours(72)).minusSeconds(1800);assertThatThrownBy(()->service.appointment(actor(n,RoleCode.CUSTOMER),n.getId(),new Appointment(clock.time,null,version(n)),"bad-slot",false)).hasMessageContaining("Entire service slot");}
    @Test void arrivalDoesNotBypassLateCompletionAndExpiryPreservesHistory(){var n=scheduled();var staff=actor(n,RoleCode.STAFF);var state=service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF);var arrival=service.arrival(staff,n.getId(),new Arrival(state.appointmentRef(),List.of(),version(n)),"arrive");clock.time=NOW.plus(Duration.ofHours(72)).plusSeconds(1);assertThatThrownBy(()->service.complete(staff,n.getId(),new Completion(version(n),true,arrival.state().arrivalRef(),UUID.randomUUID(),null),"late-complete")).hasMessageContaining("deadline");assertThat(service.expire(n.getId())).isTrue();assertThat(service.expire(n.getId())).isFalse();assertThat(n.getStatus()).isEqualTo(RenewalStatus.appointment_scheduled);assertThat(service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF).phase()).isEqualTo("SIGNING_EXPIRED");assertThat(em.find(RenewalOpenSlot.class,n.getRental().getId())).isNotNull();assertThat(n.getRental().getStorageUnit().getStatus()).isEqualTo(StorageUnitStatus.available);}
    @Test void completeAtExactDeadlinePermitted(){var n=scheduled();var staff=actor(n,RoleCode.STAFF);var state=service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF);var arrival=service.arrival(staff,n.getId(),new Arrival(state.appointmentRef(),List.of(),version(n)),"arrive");clock.time=NOW.plus(Duration.ofHours(72));assertThat(service.complete(staff,n.getId(),new Completion(version(n),true,arrival.state().arrivalRef(),UUID.randomUUID(),null),"exact").state().phase()).isEqualTo("COMPLETED");}
    @Test void revokedAssignmentBlocksReplay(){var n=scheduled();var staff=actor(n,RoleCode.STAFF);var state=service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF);var body=new Arrival(state.appointmentRef(),List.of(),version(n));service.arrival(staff,n.getId(),body,"replay");ops.authorized=false;assertThatThrownBy(()->service.arrival(staff,n.getId(),body,"replay")).hasMessageContaining("revoked");}
    @Test void cashHistoryRemainsWhenCompletionBlockedByMoneyOrReturn(){
        var n=scheduled();var staff=actor(n,RoleCode.STAFF);
        var state=service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF);
        var arrival=service.arrival(staff,n.getId(),new Arrival(state.appointmentRef(),List.of(),version(n)),"arrival");
        var proof=UUID.randomUUID();
        var recorded=service.cash(staff,n.getId(),new Cash(n.getId(),"TEST-RECEIPT",true,version(n),proof,"CASH"),"cash");
        var recordedData=(Map<?,?>)recorded.event().data();
        assertThat(recordedData.get("receiptFileId")).isEqualTo(proof.toString());
        assertThat(recordedData.get("transactionReference")).isEqualTo("TEST-RECEIPT");
        var customerHistory=service.events(actor(n,RoleCode.CUSTOMER),n.getId(),RenewalOperationService.Audience.CUSTOMER,"payments",0,20,"test");
        assertThat(((Map<?,?>)customerHistory.data().getFirst().data()).containsKey("receiptFileId")).isFalse();
        ops.fullyPaid=false;
        assertThatThrownBy(()->service.complete(staff,n.getId(),new Completion(version(n),true,arrival.state().arrivalRef(),UUID.randomUUID(),null),"debt")).hasMessageContaining("payment");
        ops.fullyPaid=true;n.getRental().setStatus(RentalStatus.return_requested);em.flush();
        assertThatThrownBy(()->service.complete(staff,n.getId(),new Completion(version(n),true,arrival.state().arrivalRef(),UUID.randomUUID(),null),"return")).hasMessageContaining("return state");
        assertThat(service.events(staff,n.getId(),RenewalOperationService.Audience.STAFF,"payments",0,20,"test").pagination().totalItems()).isEqualTo(2);
    }
    @Test void staffQueueFiltersCurrentAssignmentsBeforePagination(){var n=scheduled();scheduled();ops.assignedIds=Set.of(n.getId());var list=service.appointments(actor(n,RoleCode.STAFF),null,LocalDate.of(2026,10,8),"SIGNING",0,1,"test");assertThat(list.pagination().totalItems()).isEqualTo(1);assertThat(list.data().getFirst().renewalId()).isEqualTo(n.getId());assertThat(list.data().getFirst().appointmentEnd()).isEqualTo(NOW.plusSeconds(7200));}
    @Test void crossRenewalAppointmentAndEvidenceRejected(){var n=scheduled();var other=scheduled();var ref=service.detail(actor(other,RoleCode.CUSTOMER),other.getId(),RenewalOperationService.Audience.CUSTOMER).appointmentRef();assertThatThrownBy(()->service.incident(actor(n,RoleCode.STAFF),n.getId(),new Incident(ref,"TEST",List.of(),version(n)),"wrong-parent")).hasMessageContaining("not found");ops.unsafeEvidence=true;var own=service.detail(actor(n,RoleCode.CUSTOMER),n.getId(),RenewalOperationService.Audience.CUSTOMER).appointmentRef();assertThatThrownBy(()->service.arrival(actor(n,RoleCode.STAFF),n.getId(),new Arrival(own,List.of(UUID.randomUUID()),version(n)),"evidence")).hasMessageContaining("evidence");}
    @Test void exceptionRequiresCustomerConsentAndDoesNotResetOriginalDeadline(){var n=scheduled();var staff=actor(n,RoleCode.STAFF);var original=service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF);var incident=service.incident(staff,n.getId(),new Incident(original.appointmentRef(),"TEST facility delay",List.of(),version(n)),"incident");var revised=original.originalSigningDeadline().plusSeconds(7200);var decision=service.exception(actor(n,RoleCode.MANAGER),n.getId(),new ExceptionDecision(incident.event().id(),ExceptionAction.APPROVE_RESCHEDULE_BEFORE_CUTOFF,NOW.plus(Duration.ofHours(73)),revised,"TEST",List.of(),version(n)),"decision");assertThat(decision.state().effectiveSigningDeadline()).isEqualTo(original.effectiveSigningDeadline());var result=service.confirm(actor(n,RoleCode.CUSTOMER),n.getId(),new Confirmation(decision.event().id(),version(n)),"confirm");assertThat(result.state().effectiveSigningDeadline()).isEqualTo(revised);assertThat(result.state().originalSigningDeadline()).isEqualTo(original.originalSigningDeadline());}
    @Test void refundApprovalStaysPendingAndCustomerDoesNotSeeInternalEvidence(){var n=scheduled();var state=service.detail(actor(n,RoleCode.CUSTOMER),n.getId(),RenewalOperationService.Audience.CUSTOMER);var incident=service.incident(actor(n,RoleCode.STAFF),n.getId(),new Incident(state.appointmentRef(),"INTERNAL",List.of(),version(n)),"incident");var refund=service.refund(actor(n,RoleCode.MANAGER),n.getId(),new RefundDecision(incident.event().id(),RenewalCommands.DecisionType.APPROVE,"INTERNAL",List.of(UUID.randomUUID()),version(n)),"refund");assertThat(refund.event().data().toString()).contains("APPROVED_AWAITING_EXECUTION");var publicRows=service.events(actor(n,RoleCode.CUSTOMER),n.getId(),RenewalOperationService.Audience.CUSTOMER,"refunds",0,20,"test");assertThat(publicRows.data().toString()).doesNotContain("INTERNAL","evidenceFileIds");assertThat(n.getStatus()).isEqualTo(RenewalStatus.appointment_scheduled);}
    @Test void overdueUsesActualDebtNotFutureDebtAndStableReference(){var n=fixture();var manager=actor(n,RoleCode.MANAGER);var list=overdue.list(manager,query("ALL"),"test");assertThat(list.data()).hasSize(2);var payment=list.data().stream().filter(r->r.kind().equals("PAYMENT_DUE")).findFirst().orElseThrow();assertThat(payment.outstanding()).isEqualByComparingTo("5");assertThat(payment.recoveryEligible()).isFalse();assertThat(overdue.list(manager,query("ALL"),"test").data().stream().map(OverdueResponse::caseRef)).containsExactlyElementsOf(list.data().stream().map(OverdueResponse::caseRef).toList());}
    @Test void missingFinancialSourceIsPartialNotZeroDebt(){var n=fixture();sources.financeKnown=false;var list=overdue.list(actor(n,RoleCode.MANAGER),query("ALL"),"test");assertThat(list.completeness()).isEqualTo("PARTIAL");assertThat(list.missingSources()).contains("AUTHORITATIVE_OBLIGATIONS_ALLOCATIONS");assertThat(list.data()).allMatch(r->r.outstanding()==null);assertThatThrownBy(()->overdue.detail(actor(n,RoleCode.MANAGER),"PAYMENT_DUE:"+n.getRental().getId()+":"+sources.obligation)).hasMessageContaining("DEFERRED_SOURCE");}
    @Test void followUpNoteIdempotentVersionedAndDoesNotClearDebt(){var n=fixture();var a=actor(n,RoleCode.MANAGER);String ref="RENTAL_TERM:"+n.getRental().getId();var body=new OverdueCommands.FollowUp(OverdueCommands.Type.NOTE,"TEST contact",0L);var event=overdue.followUp(a,ref,body,"note");assertThat(event.followUpVersion()).isEqualTo(1);assertThat(overdue.followUp(a,ref,body,"note")).isEqualTo(event);assertThatThrownBy(()->overdue.followUp(a,ref,body,"new")).hasMessageContaining("stale");assertThat(overdue.history(a,ref,0,20,"test").pagination().totalItems()).isEqualTo(1);assertThat(overdue.list(a,query("PAYMENT_DUE"),"test").data()).hasSize(1);}
    @Test void reminderThrottledAcrossKeysAndRecoveryNeverUsesDebtDay8(){var n=fixture();var a=actor(n,RoleCode.MANAGER);String ref="RENTAL_TERM:"+n.getRental().getId();overdue.followUp(a,ref,new OverdueCommands.FollowUp(OverdueCommands.Type.REMINDER,"TEST",0L),"reminder");assertThatThrownBy(()->overdue.followUp(a,ref,new OverdueCommands.FollowUp(OverdueCommands.Type.REMINDER,"TEST",1L),"other")).hasMessageContaining("throttled");clock.time=NOW.plus(Duration.ofDays(10));String debt="PAYMENT_DUE:"+n.getRental().getId()+":"+sources.obligation;assertThatThrownBy(()->overdue.recovery(a,debt,new OverdueCommands.Recovery("TEST",0L),"debt-recovery")).hasMessageContaining("term expiry");var status=n.getRental().getStorageUnit().getStatus();assertThat(overdue.recovery(a,ref,new OverdueCommands.Recovery("TEST",1L),"term-recovery").externalRef()).isNotNull();assertThat(n.getRental().getStorageUnit().getStatus()).isEqualTo(status);}
    @Test void overdueWrongScopeAndMalformedQueryRejected(){var n=fixture();var wrong=new ActorPrincipal(n.getRequestedBy().getId(),UUID.randomUUID(),Set.of(RoleCode.MANAGER),Set.of(SystemPermission.VIEW_RENTALS.code()),Map.of(UUID.randomUUID(),FacilityScopeLevel.READ));assertThatThrownBy(()->overdue.detail(wrong,"RENTAL_TERM:"+n.getRental().getId())).hasMessageContaining("not found");var q=new LinkedMultiValueMap<String,String>();q.add("asOf","2099-01-01");assertThatThrownBy(()->OverdueQuery.parse(q)).isInstanceOf(ApiException.class);}
    @Test void exclusiveCompletionWritesRawEndOnceAndCanBeReadBackThroughProjectAdapter() {
        var n=scheduled(true);var r=n.getRental();var staff=actor(n,RoleCode.STAFF);
        r.getReservation().setAssignedUnit(r.getStorageUnit());r.getReservation().setStatus(ReservationStatus.COMPLETED);
        var handover=new CheckIn();handover.setReservation(r.getReservation());handover.setRental(r);handover.setPerformedBy(n.getRequestedBy());handover.setStatus(CheckInStatus.completed);em.persist(handover);em.flush();
        var state=service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF);
        var arrival=service.arrival(staff,n.getId(),new Arrival(state.appointmentRef(),List.of(),version(n)),"exclusive-arrival");
        var body=new Completion(version(n),true,arrival.state().arrivalRef(),UUID.randomUUID(),"TEST exclusive completion");
        var result=service.complete(staff,n.getId(),body,"exclusive-complete");
        assertThat(service.complete(staff,n.getId(),body,"exclusive-complete")).isEqualTo(result);
        assertThat(r.getContractEndDate()).isEqualTo(LocalDate.of(2026,11,7));assertThat(n.getNewEndDate()).isEqualTo(LocalDate.of(2026,11,6));
        assertThat(ops.consumes).isEqualTo(1);
        assertThat(new ObjectMapper().valueToTree(result.event().data()).has("rentalPeriodEvidence")).isFalse();
        em.flush();var rentalId=r.getId();em.clear();r=em.find(Rental.class,rentalId);
        var adapter=new com.storagehub.service.rental.period.ProjectHandoffRentalPeriodAdapter(em,new ObjectMapper().findAndRegisterModules());
        var dates=adapter.read(r).orElseThrow();assertThat(dates.convention()).isEqualTo(com.storagehub.service.rental.period.RentalPeriod.Convention.EXCLUSIVE);
        assertThat(dates.inclusiveEndDate()).isEqualTo(LocalDate.of(2026,11,6));assertThat(dates.storedEndDate()).isEqualTo(LocalDate.of(2026,11,7));
    }
    private OverdueQuery query(String kind){return new OverdueQuery(0,20,null,kind,"","priority",true);}
    private long version(Renewal n){return em.find(RenewalWorkflow.class,n.getId()).getVersion();}
    private Renewal paid(){return paid(false);}
    private Renewal paid(boolean exclusive){var n=fixture(exclusive);service.deposit(actor(n,RoleCode.CUSTOMER),n.getId(),new RenewalOperationCommands.Version(version(n)),"pay-"+n.getId());return n;}
    private Renewal scheduled(){return scheduled(false);}
    private Renewal scheduled(boolean exclusive){var n=paid(exclusive);service.appointment(actor(n,RoleCode.CUSTOMER),n.getId(),new Appointment(NOW.plusSeconds(3600),null,version(n)),"appt-"+n.getId(),false);return n;}
    private ActorPrincipal actor(Renewal n,RoleCode role){return new ActorPrincipal(n.getRequestedBy().getId(),UUID.randomUUID(),Set.of(role),Set.of(SystemPermission.VIEW_RENTALS.code(),SystemPermission.MANAGE_RENTALS.code()),role==RoleCode.CUSTOMER?Map.of():Map.of(n.getRental().getFacility().getId(),FacilityScopeLevel.MANAGE));}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"RETURNED","MAINTENANCE","RECOVERY","BOOKING"})
    void d3GuardBlocksDepositAndCompletionWithoutWritingMoneyOrEndDate(String kind){
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();
        factory.addBean("guard",new com.storagehub.service.renewal.integration.RenewalOperationalGuard(em));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"operationalGuards",factory.getBeanProvider(com.storagehub.service.renewal.integration.RenewalOperationalGuard.class));
        try {
            var n=fixture();var r=n.getRental();r.getStorageUnit().setStatus(StorageUnitStatus.occupied);em.flush();
            var staff=actor(n,RoleCode.STAFF);var customer=actor(n,RoleCode.CUSTOMER);
            service.deposit(customer,n.getId(),new RenewalOperationCommands.Version(version(n)),"before-conflict");
            service.appointment(customer,n.getId(),new Appointment(NOW.plusSeconds(3600),null,version(n)),"appt",false);
            var state=service.detail(staff,n.getId(),RenewalOperationService.Audience.STAFF);
            var arrival=service.arrival(staff,n.getId(),new Arrival(state.appointmentRef(),List.of(),version(n)),"arrival");
            var pending=fixture();pending.getRental().getStorageUnit().setStatus(StorageUnitStatus.occupied);
            for(var rental:List.of(r,pending.getRental()))switch(kind){
                case "RETURNED" -> rental.setActualReturnedAt(NOW);
                case "MAINTENANCE" -> {var task=new MaintenanceTask();task.setFacility(rental.getFacility());task.setStorageUnit(rental.getStorageUnit());task.setTitle("TEST");task.setReason("TEST");task.setReportedBy(rental.getCustomer());em.persist(task);}
                case "RECOVERY" -> em.persist(new com.storagehub.service.overdue.OverdueFollowUp("RENTAL_TERM:"+rental.getId(),rental,"RECOVERY_HANDOFF","TEST",rental.getCustomer().getId(),NOW,UUID.randomUUID(),"TEST","1"));
                case "BOOKING" -> {var other=new Reservation();other.setReservationCode("OTHER-"+rental.getId().toString().substring(0,8));other.setIdempotencyKey("OTHER-"+rental.getId());other.setCustomer(rental.getCustomer());other.setSourceQuote(rental.getReservation().getSourceQuote());other.setFacility(rental.getFacility());other.setUnitType(rental.getStorageUnit().getUnitType());other.setAssignedUnit(rental.getStorageUnit());other.setStartDate(rental.getStartDate());other.setEndDate(rental.getContractEndDate());other.setStatus(ReservationStatus.CONFIRMED);em.persist(other);}
            }
            em.flush();var oldEnd=r.getContractEndDate();int before=ops.deposits;
            String expected=switch(kind){case "RETURNED" -> "return state changed";case "MAINTENANCE" -> "Active maintenance";case "RECOVERY" -> "Recovery handoff";default -> "Another booking";};
            assertThatThrownBy(()->service.deposit(actor(pending,RoleCode.CUSTOMER),pending.getId(),new RenewalOperationCommands.Version(version(pending)),"blocked")).hasMessageContaining(expected);
            assertThatThrownBy(()->service.complete(staff,n.getId(),new Completion(version(n),true,arrival.state().arrivalRef(),UUID.randomUUID(),null),"blocked")).hasMessageContaining(expected);
            assertThat(ops.deposits).isEqualTo(before);assertThat(ops.consumes).isZero();assertThat(r.getContractEndDate()).isEqualTo(oldEnd);
            assertThat(n.getStatus()).isEqualTo(RenewalStatus.appointment_scheduled);assertThat(pending.getStatus()).isEqualTo(RenewalStatus.approved);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(service,"operationalGuards",new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(com.storagehub.service.renewal.integration.RenewalOperationalGuard.class));
        }
    }
    @Test void fullRentSigningExpiryUsesSigningDeadlineAndPreservesPaidRights() {
        var n=fixture();var s=new RenewalOperationState(n);
        s.fullRent(UUID.randomUUID(),NOW,NOW.plus(Duration.ofHours(72)),NOW.plus(Duration.ofHours(72)),"TEST","v1");
        em.persist(s);em.flush();
        clock.time=NOW.plus(Duration.ofHours(25));
        assertThat(service.expire(n.getId())).isFalse();
        clock.time=NOW.plus(Duration.ofHours(72)).plusSeconds(1);
        assertThat(service.expire(n.getId())).isTrue();
        assertThat(s.getPhase()).isEqualTo(RenewalOperationState.Phase.SIGNING_EXPIRED);
        assertThat(n.getStatus()).isNotEqualTo(RenewalStatus.payment_expired);
        assertThat(s.getFullRentPaidAt()).isEqualTo(NOW);
        assertThat(em.find(RenewalOpenSlot.class,n.getRental().getId())).isNotNull();
    }
    private Renewal fixture(){return fixture(false);}
    private Renewal fixture(boolean exclusive){
        String suffix=UUID.randomUUID().toString().substring(0,8);Facility f=new Facility();f.setCode(suffix);f.setName("TEST");f.setAddress("TEST");f.setCity("TEST");em.persist(f);
        User u=new User();u.setEmail(suffix+"@test.invalid");u.setFullName("TEST Customer");u.setPasswordHash("test-only");em.persist(u);
        UnitType t=new UnitType();t.setFacility(f);t.setCode("TEST");t.setName("TEST");t.setLengthM(BigDecimal.ONE);t.setWidthM(BigDecimal.ONE);t.setHeightM(BigDecimal.ONE);t.setMonthlyPrice(new BigDecimal("100"));t.setMaxLoadKg(BigDecimal.ONE);t.setRackLengthM(BigDecimal.ONE);t.setRackWidthM(BigDecimal.ONE);t.setRackHeightM(BigDecimal.ONE);em.persist(t);
        StorageUnit unit=new StorageUnit();unit.setFacility(f);unit.setUnitType(t);unit.setCode("TEST-"+suffix);em.persist(unit);
        var q=new ReservationQuote();q.setCustomer(u);q.setFacility(f);q.setUnitType(t);q.setPricingPackageCode("TEST");q.setPolicyVersion("TEST");q.setStartDate(LocalDate.of(2026,10,1));q.setEndDate(LocalDate.of(2026,10,7));q.setRentalMonths(1);q.setMonthlyPrice(new BigDecimal("100"));q.setSubtotal(new BigDecimal("100"));q.setDiscountRate(BigDecimal.ZERO);q.setDiscountAmount(BigDecimal.ZERO);q.setTotalAfterDiscount(new BigDecimal("100"));q.setReservationDepositAmount(BigDecimal.ZERO);q.setSecurityDepositAmount(BigDecimal.ZERO);q.setRemainingRentalAmount(new BigDecimal("100"));q.setDueAtCheckIn(new BigDecimal("100"));q.setTotalInitialObligation(new BigDecimal("100"));q.setQuotedAt(NOW);q.setExpiresAt(NOW.plusSeconds(1800));em.persist(q);
        var res=new Reservation();res.setReservationCode(suffix);res.setCustomer(u);res.setSourceQuote(q);res.setIdempotencyKey(suffix);res.setFacility(f);res.setUnitType(t);res.setStartDate(q.getStartDate());res.setEndDate(q.getEndDate());em.persist(res);
        var r=new Rental();r.setCustomer(u);r.setFacility(f);r.setStorageUnit(unit);r.setReservation(res);r.setStartDate(q.getStartDate());r.setContractEndDate(exclusive?LocalDate.of(2026,10,7):LocalDate.of(2026,10,6));r.setMonthlyPrice(new BigDecimal("100"));em.persist(r);em.flush();if(exclusive)dateEvidence.exclusive.add(r.getId());
        var terms=new RenewalQuoteResponse.Terms(r.getContractEndDate(),LocalDate.of(2026,10,7),LocalDate.of(2026,11,7),LocalDate.of(2026,11,6),t.getId(),"TEST",UUID.randomUUID(),"v1",1,new BigDecimal("100"),BigDecimal.ZERO,new BigDecimal("100"),BigDecimal.ZERO,new BigDecimal("100"),new BigDecimal("20"),new BigDecimal("80"),"VND","TEST","v1",unit.getId(),f.getId(),exclusive?"EXCLUSIVE":"INCLUSIVE",r.getStartDate());
        var quote=store.storeQuote(r,u,terms,NOW,NOW.plusSeconds(1800));var n=new Renewal();n.setRental(r);n.setRequestedBy(u);n.setAmount(terms.totalAfterDiscount());n.setNewEndDate(terms.newEndDate());em.persist(n);var wf=store.acceptInitial(r,n,quote,NOW,"TEST");n.setStatus(RenewalStatus.approved);wf.reviewed(u,NOW,"TEST",NOW.plusSeconds(86400),UUID.randomUUID());em.flush();return n;
    }
}
