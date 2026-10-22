package com.storagehub.service.support;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.storagehub.service.support.SupportService.Audience.*;
import static com.storagehub.api.support.SupportCommands.*;
import static com.storagehub.service.support.SupportSources.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.support.*;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.*;
import com.storagehub.domain.model.Role;
import com.storagehub.security.*;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.NotificationService;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;

/** All records/policy/receiver fixtures exist only in the rollback-only H2 test context. */
@DataJpaTest(showSql=false) @ActiveProfiles("test")
@Import({com.storagehub.testsupport.DisplayCodeTestConfiguration.class,SupportService.class,SupportPersistence.class,SupportWorkflowIntegrationTests.Sources.class})
class SupportWorkflowIntegrationTests {
    static final Instant NOW=Instant.parse("2026-10-08T03:00:00Z");
    @TestConfiguration static class Sources {
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean AuditLogService audit(){return mock(AuditLogService.class);}
        @Bean NotificationService notifications(){return mock(NotificationService.class);}
        @Bean EvidenceSource evidence(){return mock(EvidenceSource.class);}
        @Bean TestClock clock(){return new TestClock();}
        @Bean TestClose closePolicy(){return new TestClose();}
        @Bean TestReceiver receiver(){return new TestReceiver();}
        @Bean TestNotice reviewNotices(EntityManager em){return new TestNotice(em);}
    }
    static class TestClock extends Clock {
        Instant time=NOW;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(time,zone);}
        public Instant instant(){return time;}
    }
    static class TestClose implements ClosePolicySource {
        boolean available; int days=7;
        public Optional<CloseRule> read(SupportTicket t,SupportState s,Instant now){
            return available?Optional.of(new CloseRule("test-only-policy","v1",true,s.getResolvedAt().plus(Duration.ofDays(days)))):Optional.empty();
        }
    }
    static class TestReceiver implements EscalationSource {
        boolean enabled,forged; String status; UUID reference=UUID.randomUUID(); int routes;
        public boolean atomic(){return enabled;}
        public boolean supports(SupportCommands.Module module){return enabled;}
        public UUID route(SupportTicket t,SupportEscalation e,UUID manager,String key,Instant now){routes++;return reference;}
        public Optional<ReceiverResult> result(SupportTicket t,SupportEscalation e){
            return status==null?Optional.empty():Optional.of(new ReceiverResult(forged?UUID.randomUUID():t.getId(),e.getId(),reference,status,status.equals("COMPLETED")?reference:null));
        }
    }
    static class TestNotice implements NotificationSource {
        final EntityManager em;boolean available;String mode="";ReviewNotice supplied;int reads;
        TestNotice(EntityManager em){this.em=em;}
        public boolean consistentThroughClose(){return available&&!mode.equals("NON_ATOMIC");}
        public Optional<ReviewNotice> reviewNotice(SupportTicket t,UUID resolutionEventId,CloseRule rule,Instant now){
            reads++;if(mode.equals("MISSING"))return Optional.empty();
            if(mode.equals("ERROR"))throw new IllegalStateException("Test-only delivery adapter failed");
            if(supplied!=null)return Optional.of(supplied);
            var resolvedAt=em.find(SupportState.class,t.getId()).getResolvedAt();
            return Optional.of(new ReviewNotice(mode.equals("NO_REFERENCE")?null:UUID.randomUUID(),
                mode.equals("WRONG_TICKET")?UUID.randomUUID():t.getId(),mode.equals("WRONG_CUSTOMER")?UUID.randomUUID():t.getCustomer().getId(),
                mode.equals("WRONG_RESOLUTION")?UUID.randomUUID():resolutionEventId,
                mode.equals("WRONG_POLICY")?"different-policy":rule.policyRef(),mode.equals("WRONG_VERSION")?"different-version":rule.policyVersion(),
                mode.equals("NO_TIME")?null:mode.equals("FUTURE")?now.plusSeconds(1):mode.equals("BEFORE_RESOLUTION")?resolvedAt.minusSeconds(1):resolvedAt));
        }
    }
    @Autowired EntityManager em;
    @Autowired SupportService service;
    @Autowired SupportPersistence receipts;
    @Autowired NotificationService notifications;
    @Autowired EvidenceSource evidence;
    @Autowired TestClock clock;
    @Autowired TestClose close;
    @Autowired TestReceiver receiver;
    @Autowired TestNotice notice;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired javax.sql.DataSource datasource;
    Facility facility,otherFacility;
    User customer,otherCustomer,manager,staff,otherStaff,foreignStaff;
    ActorPrincipal c,m,s,s2;
    SupportQuery query=SupportQuery.parse(new LinkedMultiValueMap<>(),false,false);

    @BeforeEach void fixture(){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()){
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->fixture());return;
        }
        reset(notifications,evidence);clock.time=NOW;close.available=false;close.days=7;receiver.enabled=false;receiver.forged=false;receiver.status=null;receiver.routes=0;
        notice.available=false;notice.mode="";notice.supplied=null;notice.reads=0;
        var view=permission(SystemPermission.VIEW_SUPPORT);var manage=permission(SystemPermission.MANAGE_SUPPORT);
        var cr=role(RoleCode.CUSTOMER,Set.of());var mr=role(RoleCode.MANAGER,Set.of(view,manage));var sr=role(RoleCode.STAFF,Set.of(view,manage));
        facility=facility("TEST-A");otherFacility=facility("TEST-B");
        customer=user("customer",cr);otherCustomer=user("other-customer",cr);manager=user("manager",mr);
        staff=user("staff",sr);otherStaff=user("staff-2",sr);foreignStaff=user("foreign-staff",sr);
        scope(manager,facility,FacilityScopeLevel.MANAGE);scope(staff,facility,FacilityScopeLevel.OPERATE);
        scope(otherStaff,facility,FacilityScopeLevel.OPERATE);scope(foreignStaff,otherFacility,FacilityScopeLevel.OPERATE);em.flush();
        c=actor(customer,RoleCode.CUSTOMER,null);m=actor(manager,RoleCode.MANAGER,facility);s=actor(staff,RoleCode.STAFF,facility);s2=actor(otherStaff,RoleCode.STAFF,facility);
    }
    @Test @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void assignedStaffNameComesFromCommittedAssignmentInHttpListAndDetail() throws Exception {
        var created=service.create(c,new Create("Assignee name readback","TEST ONLY",facility.getId(),null,List.of()),"name-create");
        assertThat(created.assignedStaffName()).isNull();
        service.claim(s,created.id(),new SupportCommands.Version(created.version()),"name-claim");
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
            em.find(User.class,staff.getId()).setFullName("Assigned Staff Readback");
        });
        var context=mock(ActorContext.class);when(context.required()).thenReturn(m);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ManagerSupportController(context,service))
            .setControllerAdvice(new com.storagehub.common.api.GlobalExceptionHandler()).build();
        var mapper=new ObjectMapper().findAndRegisterModules();
        var detail=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/manager/support-tickets/"+created.id())).andReturn().getResponse();
        assertThat(detail.getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(detail.getContentAsString()).path("data").path("assignedStaffName").asText()).isEqualTo("Assigned Staff Readback");
        var list=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/manager/support-tickets")
            .param("facilityId",facility.getId().toString()).param("search","Assignee name readback")).andReturn().getResponse();
        assertThat(list.getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(list.getContentAsString()).path("data").get(0).path("assignedStaffName").asText()).isEqualTo("Assigned Staff Readback");
        try(var connection=datasource.getConnection();var sql=connection.prepareStatement("select u.full_name from support_tickets t join users u on u.id=t.assigned_to where t.id=?")) {
            sql.setBytes(1,java.nio.ByteBuffer.allocate(16).putLong(created.id().getMostSignificantBits()).putLong(created.id().getLeastSignificantBits()).array());
            try(var rows=sql.executeQuery()){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("Assigned Staff Readback");}
        }
    }
    @Test void managerConversationIsPrivateAndIdempotent(){
        var t=service.create(c,new Create("Private conversation","Customer question",facility.getId(),null,List.of()),"chat-create");
        service.claim(s,t.id(),new SupportCommands.Version(t.version()),"chat-claim");
        clearInvocations(notifications);
        var body=new StaffMessage("Internal guidance",List.of(),Visibility.INTERNAL);
        var message=service.managerMessage(m,t.id(),body,"manager-chat");
        assertThat(message.authorRole()).isEqualTo("MANAGER");
        assertThat(service.managerMessage(m,t.id(),body,"manager-chat").id()).isEqualTo(message.id());
        assertThat(service.messages(c,CUSTOMER,t.id(),query,"test").data()).noneMatch(row->row.id().equals(message.id()));
        assertThat(service.messages(s,STAFF,t.id(),query,"test",Visibility.INTERNAL).data()).extracting(SupportResponse.Message::id).containsExactly(message.id());
        assertThat(service.messages(s,STAFF,t.id(),query,"test",Visibility.PUBLIC).data()).noneMatch(row->row.id().equals(message.id()));
        assertThat(service.detail(m,MANAGER,t.id()).status()).isEqualTo(SupportTicketStatus.in_progress);
        verify(notifications).createNotification(eq(staff.getId()),eq(NotificationType.SUPPORT),eq("Quản lý đã gửi tin nhắn hỗ trợ"),any(),eq(t.id()));
        verifyNoMoreInteractions(notifications);
    }
    @Test void managerCannotSendPublicSupportMessages(){
        var t=service.create(c,new Create("Private conversation","Customer question",facility.getId(),null,List.of()),"chat-create");
        service.claim(s,t.id(),new SupportCommands.Version(t.version()),"chat-claim");
        assertThatThrownBy(()->service.managerMessage(m,t.id(),new StaffMessage("Public reply",List.of(),Visibility.PUBLIC),"manager-public"))
            .isInstanceOf(ApiException.class).hasMessageContaining("internal");
    }
    @Test void createDerivesHonestUnknownSlaAndIdempotentResult(){
        var body=new Create("Access question","Please help",facility.getId(),null,List.of());
        var t=service.create(c,body,"create");
        assertThat(t.workflowReady()).isTrue();assertThat(t.status()).isEqualTo(SupportTicketStatus.open);
        assertThat(t.slaCompleteness()).isEqualTo("UNKNOWN");assertThat(t.sla()).isNull();
        assertThat(service.create(c,body,"create").id()).isEqualTo(t.id());
        assertThat(count("SupportTicket")).isEqualTo(1);assertThat(count("SupportMessage")).isEqualTo(1);
        assertThatThrownBy(()->service.create(c,new Create("Changed","Please help",facility.getId(),null,List.of()),"create")).isInstanceOf(ApiException.class).hasMessageContaining("key");
    }
    @Test void facilityIsRequiredWithoutOwnedLink(){
        assertThatThrownBy(()->service.create(c,new Create("Question","Help",null,null,List.of()),"missing")).isInstanceOf(ApiException.class).hasMessageContaining("Facility required");
        assertThat(count("SupportTicket")).isZero();
    }
    @Test void unownedLinkedRecordCannotBeUsedToChooseFacility(){
        assertThatThrownBy(()->service.create(c,new Create("Question","Help",facility.getId(),new Link(LinkType.PAYMENT,UUID.randomUUID()),List.of()),"unowned")).isInstanceOf(ApiException.class).hasMessageContaining("Owned linked");
        assertThat(count("SupportTicket")).isZero();
    }
    @Test void inactiveFacilityRejected(){
        facility.setStatus(FacilityStatus.inactive);em.flush();
        assertThatThrownBy(()->create()).isInstanceOf(ApiException.class).hasMessageContaining("not active");
    }
    @Test void ownershipAndFacilityScopeApplyBeforeCountAndPagination(){
        var t=create();service.create(actor(otherCustomer,RoleCode.CUSTOMER,null),new Create("Private","Other owner",otherFacility.getId(),null,List.of()),"other");
        assertThat(service.list(c,CUSTOMER,query,"test").pagination().totalItems()).isEqualTo(1);
        assertThat(service.list(m,MANAGER,query,"test").data()).extracting(SupportResponse::id).containsExactly(t.id());
        assertThatThrownBy(()->service.detail(actor(otherCustomer,RoleCode.CUSTOMER,null),CUSTOMER,t.id())).isInstanceOf(ApiException.class);
        var foreign=actor(foreignStaff,RoleCode.STAFF,otherFacility);
        assertThatThrownBy(()->service.detail(foreign,STAFF,t.id())).isInstanceOf(ApiException.class);
    }
    @Test void customerCanFilterOwnedTicketsByFacilityWithoutStaffScope(){
        var owned=create();
        service.create(actor(otherCustomer,RoleCode.CUSTOMER,null),new Create("Other","Other owner",otherFacility.getId(),null,List.of()),"other-facility");
        var params=new LinkedMultiValueMap<String,String>();params.add("facilityId",facility.getId().toString());
        var ownFacility=SupportQuery.parseCustomerList(params);
        assertThat(service.list(c,CUSTOMER,ownFacility,"test").data()).extracting(SupportResponse::id).containsExactly(owned.id());
        params.set("facilityId",otherFacility.getId().toString());
        assertThat(service.list(c,CUSTOMER,SupportQuery.parseCustomerList(params),"test").pagination().totalItems()).isZero();
        assertThatThrownBy(()->service.list(m,MANAGER,SupportQuery.parse(params,true,false),"test"))
            .isInstanceOf(ApiException.class).hasMessageContaining("scope");
    }
    @Test void customerSupportHttpFacilityFilterPreservesOwnership() throws Exception {
        var owned=create();
        service.create(actor(otherCustomer,RoleCode.CUSTOMER,null),new Create("Other","Other owner",otherFacility.getId(),null,List.of()),"other-http");
        var context=mock(ActorContext.class);when(context.required()).thenReturn(c);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new CustomerSupportController(context,service))
            .setControllerAdvice(new SupportRequestAdvice(new ObjectMapper().findAndRegisterModules()),new com.storagehub.common.api.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/customer/support-workflows")
                .param("facilityId",facility.getId().toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("data[0].id").value(owned.id().toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("pagination.totalItems").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/customer/support-workflows")
                .param("facilityId",otherFacility.getId().toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("pagination.totalItems").value(0));
    }
    @Test @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void customerFacilityHttpFilterHasCommittedDatabaseReadback() throws Exception {
        var owned=create();
        service.create(actor(otherCustomer,RoleCode.CUSTOMER,null),new Create("Other","Other owner",otherFacility.getId(),null,List.of()),"other-committed");
        var context=mock(ActorContext.class);when(context.required()).thenReturn(c);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new CustomerSupportController(context,service))
            .setControllerAdvice(new SupportRequestAdvice(new ObjectMapper().findAndRegisterModules()),new com.storagehub.common.api.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/customer/support-workflows")
                .param("facilityId",facility.getId().toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("data[0].id").value(owned.id().toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("pagination.totalItems").value(1));
        try(var connection=datasource.getConnection();
            var statement=connection.prepareStatement("select count(*) from support_tickets");var rows=statement.executeQuery()){
            rows.next();assertThat(rows.getLong(1)).isEqualTo(2);
        }
        assertThat(service.detail(c,CUSTOMER,owned.id()).id()).isEqualTo(owned.id());
    }
    @Test void legacyNullFacilityIsNotImplicitlyTriagedOrBackfilled(){
        var legacy=new SupportTicket();legacy.setCustomer(customer);legacy.setSubject("Legacy");legacy.setDescription("Unscoped");em.persist(legacy);em.flush();
        assertThat(service.list(c,CUSTOMER,query,"test").pagination().totalItems()).isZero();
        assertThatThrownBy(()->service.detail(m,MANAGER,legacy.getId())).isInstanceOf(ApiException.class);
        assertThat(count("SupportState")).isZero();assertThat(legacy.getFacility()).isNull();
    }
    @Test void legacyScopedTicketIsReadOnlyUntilVerifiedMetadataExists(){
        var legacy=new SupportTicket();legacy.setCustomer(customer);legacy.setFacility(facility);legacy.setSubject("Legacy");legacy.setDescription("Needs migration");em.persist(legacy);em.flush();
        assertThat(service.detail(m,MANAGER,legacy.getId()).workflowReady()).isFalse();
        assertThatThrownBy(()->service.claim(s,legacy.getId(),new SupportCommands.Version(0L),"legacy")).isInstanceOf(ApiException.class).hasMessageContaining("DEFERRED_SOURCE");
        assertThat(count("SupportState")).isZero();
    }
    @Test void assignmentRequiresActualEligibleStaffIdAtSameFacility(){
        var t=create();
        assertThatThrownBy(()->service.assign(m,t.id(),new Assignment(foreignStaff.getId(),t.version(),"Wrong facility"),"foreign")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.assign(m,t.id(),new Assignment(customer.getId(),t.version(),"Not Staff"),"customer")).isInstanceOf(ApiException.class);
        staff.setStatus(UserStatus.DISABLED);em.flush();
        assertThatThrownBy(()->service.assign(m,t.id(),new Assignment(staff.getId(),t.version(),"Inactive"),"inactive")).isInstanceOf(ApiException.class);
        assertThat(em.find(SupportTicket.class,t.id()).getAssignedTo()).isNull();
    }

    @Test void openRequiresStaffAcceptanceBeforeResolution(){
        var t=assigned();
        assertThatThrownBy(()->service.resolve(s,t.id(),new Resolution("Done",List.of(),t.version()),"too-early")).isInstanceOf(ApiException.class);
        var accepted=service.accept(s,t.id(),new SupportCommands.Version(t.version()),"accept");
        assertThat(accepted.status()).isEqualTo(SupportTicketStatus.in_progress);assertThat(accepted.acceptedAt()).isEqualTo(NOW);
        assertThat(service.accept(s,t.id(),new SupportCommands.Version(t.version()),"accept").version()).isEqualTo(accepted.version());
        assertThatThrownBy(()->service.accept(s,t.id(),new SupportCommands.Version(t.version()),"stale")).isInstanceOf(ApiException.class).hasMessageContaining("stale");
    }
    @Test void unassignedQueueExposesOnlySafeMetadataAndClaimIsAtomicAndReplayable(){
        var created=create();
        var queue=service.queue(s,query,"test");
        assertThat(queue.data()).extracting(SupportQueueItem::id).containsExactly(created.id());
        assertThat(queue.data().get(0).version()).isEqualTo(created.version());
        var claimed=service.claim(s,created.id(),new SupportCommands.Version(created.version()),"claim-once");
        assertThat(claimed.status()).isEqualTo(SupportTicketStatus.in_progress);
        assertThat(claimed.assignedStaffId()).isEqualTo(staff.getId());
        assertThat(claimed.acceptedAt()).isEqualTo(NOW);
        assertThat(claimed.assignmentRevision()).isEqualTo(1);
        assertThat(service.queue(s2,query,"test").data()).isEmpty();
        assertThat(service.list(s,STAFF,query,"test").data()).extracting(SupportResponse::id).containsExactly(created.id());
        long events=count("SupportEvent");
        assertThat(service.claim(s,created.id(),new SupportCommands.Version(created.version()),"claim-once").version()).isEqualTo(claimed.version());
        assertThat(count("SupportEvent")).isEqualTo(events);
        assertThatThrownBy(()->service.claim(s2,created.id(),new SupportCommands.Version(created.version()),"other-claim"))
            .isInstanceOf(ApiException.class);
    }
    @Test void queueAndClaimRespectCurrentFacilityAndPermission(){
        var created=create();
        var foreign=actor(foreignStaff,RoleCode.STAFF,otherFacility);
        assertThat(service.queue(foreign,query,"test").data()).isEmpty();
        assertThatThrownBy(()->service.claim(foreign,created.id(),new SupportCommands.Version(created.version()),"foreign"))
            .isInstanceOf(ApiException.class);
        assertThat(service.summary(m,facility.getId()).unassignedOpen()).isEqualTo(1);
        assertThat(service.summary(m,facility.getId()).counts().get("open")).isEqualTo(1);
        assertThatThrownBy(()->service.summary(m,otherFacility.getId())).isInstanceOf(ApiException.class);
        em.createQuery("delete from UserFacilityScope x where x.user.id=:id").setParameter("id",staff.getId()).executeUpdate();em.flush();
        assertThat(service.queue(s,query,"test").data()).isEmpty();
        assertThatThrownBy(()->service.claim(s,created.id(),new SupportCommands.Version(created.version()),"revoked"))
            .isInstanceOf(ApiException.class);
    }
    @Test void assignedOrStaleTicketCannotBeClaimed(){
        var created=create();
        var assigned=legacyAssignment(created,staff);
        assertThat(service.queue(s2,query,"test").data()).isEmpty();
        assertThatThrownBy(()->service.claim(s2,created.id(),new SupportCommands.Version(created.version()),"claim-assigned"))
            .isInstanceOf(ApiException.class);
        assertThat(service.accept(s,created.id(),new SupportCommands.Version(assigned.version()),"legacy-accept").status())
            .isEqualTo(SupportTicketStatus.in_progress);
    }
    @Test @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void concurrentClaimsHaveOneWinnerAndFailedNotificationRollsBack() throws Exception {
        var created=create();
        doThrow(new IllegalStateException("Test-only delivery failure")).when(notifications)
            .createNotification(eq(customer.getId()),eq(NotificationType.SUPPORT),anyString(),anyString(),eq(created.id()));
        assertThatThrownBy(()->service.claim(s,created.id(),new SupportCommands.Version(created.version()),"claim-failed"))
            .isInstanceOf(IllegalStateException.class);
        reset(notifications);
        assertThat(service.detail(c,CUSTOMER,created.id()).assignedStaffId()).isNull();
        assertThat(service.queue(s,query,"test").pagination().totalItems()).isEqualTo(1);
        var start=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->{start.await();try{return service.claim(s,created.id(),new SupportCommands.Version(created.version()),"claim-staff-1");}catch(ApiException e){return null;}});
            var second=pool.submit(()->{start.await();try{return service.claim(s2,created.id(),new SupportCommands.Version(created.version()),"claim-staff-2");}catch(ApiException e){return null;}});
            start.countDown();
            var a=first.get(30,java.util.concurrent.TimeUnit.SECONDS);
            var b=second.get(30,java.util.concurrent.TimeUnit.SECONDS);
            assertThat((a==null?0:1)+(b==null?0:1)).isEqualTo(1);
            var winner=a==null?b:a;
            assertThat(service.detail(c,CUSTOMER,created.id()).assignedStaffId()).isEqualTo(winner.assignedStaffId());
            assertThat(service.queue(s,query,"test").data()).isEmpty();
        } finally {pool.shutdownNow();}
    }
    @Test void managerCannotImpersonateAssignedStaffToComplete(){
        var t=active();
        assertThatThrownBy(()->service.resolve(m,t.id(),new Resolution("Manager done",List.of(),t.version()),"manager-complete")).isInstanceOf(ApiException.class);
        assertThat(service.detail(c,CUSTOMER,t.id()).status()).isEqualTo(SupportTicketStatus.in_progress);
    }

    @Test void sameAssigneeCannotSilentlyResetAcceptedWork(){
        var t=active();
        assertThatThrownBy(()->service.assign(m,t.id(),new Assignment(staff.getId(),t.version(),"Reset"),"same")).isInstanceOf(ApiException.class);
        assertThat(service.detail(c,CUSTOMER,t.id()).acceptedAt()).isNotNull();
    }
    @Test void revokedDatabaseScopeOverridesStalePrincipal(){
        var t=active();em.createQuery("delete from UserFacilityScope x where x.user.id=:id").setParameter("id",staff.getId()).executeUpdate();em.flush();
        assertThatThrownBy(()->service.detail(s,STAFF,t.id())).isInstanceOf(ApiException.class);
        assertThat(service.list(s,STAFF,query,"test").pagination().totalItems()).isZero();
    }
    @Test void missingRealStaffPermissionCannotBeBypassedByPrincipal(){
        var t=active();var role=staff.getRoles().iterator().next();role.getPermissions().removeIf(p->p.getCode().equals(SystemPermission.MANAGE_SUPPORT.code()));em.flush();
        assertThatThrownBy(()->service.staffMessage(s,t.id(),new StaffMessage("Reply",List.of(),Visibility.PUBLIC),"revoked")).isInstanceOf(ApiException.class);
    }
    @Test void internalMessagesDoNotLeakContentCountsOrNotifyCustomer(){
        var t=active();reset(notifications);
        service.staffMessage(s,t.id(),new StaffMessage("Private coordination",List.of(),Visibility.INTERNAL),"internal");
        var publicMessages=service.messages(c,CUSTOMER,t.id(),query,"test");
        assertThat(publicMessages.pagination().totalItems()).isEqualTo(1);
        assertThat(publicMessages.data()).extracting(SupportResponse.Message::body).doesNotContain("Private coordination");
        assertThat(service.messages(m,MANAGER,t.id(),query,"test").pagination().totalItems()).isEqualTo(2);
        verify(notifications).createNotification(eq(manager.getId()),eq(NotificationType.SUPPORT),eq("Tin nhắn mới từ nhân viên về yêu cầu hỗ trợ"),any(),eq(t.id()));
        verifyNoMoreInteractions(notifications);
        assertThatThrownBy(()->service.events(c,CUSTOMER,t.id(),query,"test")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.escalations(c,CUSTOMER,t.id(),query,"test")).isInstanceOf(ApiException.class);
    }
    @Test void waitingCustomerResumesOnlyOnVersionedPublicCustomerReply(){
        var t=active();var waiting=service.requestInformation(s,t.id(),new Information("Need details",List.of(),t.version()),"ask");
        service.staffMessage(s,t.id(),new StaffMessage("Internal check",List.of(),Visibility.INTERNAL),"internal");
        assertThat(service.detail(c,CUSTOMER,t.id()).status()).isEqualTo(SupportTicketStatus.waiting_customer);
        assertThatThrownBy(()->service.customerMessage(c,t.id(),new CustomerMessage("Details",List.of(),null),"no-version")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.customerMessage(c,t.id(),new CustomerMessage("Details",List.of(),waiting.version()),"stale-version")).isInstanceOf(ApiException.class);
        var replyVersion=service.detail(c,CUSTOMER,t.id()).version();
        var reply=service.customerMessage(c,t.id(),new CustomerMessage("Details",List.of(),replyVersion),"details");
        assertThat(service.customerMessage(c,t.id(),new CustomerMessage("Details",List.of(),replyVersion),"details").id()).isEqualTo(reply.id());
        var current=service.detail(c,CUSTOMER,t.id());assertThat(current.status()).isEqualTo(SupportTicketStatus.in_progress);
        assertThat(current.version()).isGreaterThan(waiting.version());
    }
    @Test void resolvedReplyDoesNotImplicitlyReopen(){
        var t=resolved();service.customerMessage(c,t.id(),new CustomerMessage("Thanks",List.of(),null),"thanks");
        assertThat(service.detail(c,CUSTOMER,t.id()).status()).isEqualTo(SupportTicketStatus.resolved);
        assertThatThrownBy(()->service.reopen(c,t.id(),new Reopen("Still broken",t.version()),"stale-reopen")).isInstanceOf(ApiException.class);
        var reopened=service.reopen(c,t.id(),new Reopen("Still broken",service.detail(c,CUSTOMER,t.id()).version()),"reopen");
        assertThat(reopened.status()).isEqualTo(SupportTicketStatus.in_progress);assertThat(reopened.resolvedAt()).isNull();
    }
    @Test void reopenWithIneligiblePreviousStaffReturnsToUnassignedStaffQueue(){
        var t=resolved();staff.setStatus(UserStatus.DISABLED);em.flush();
        var reopened=service.reopen(c,t.id(),new Reopen("Needs more work",t.version()),"reopen");
        assertThat(reopened.status()).isEqualTo(SupportTicketStatus.open);assertThat(reopened.assignedStaffId()).isNull();assertThat(reopened.acceptedAt()).isNull();
    }
    @Test void closeRequiresAuthoritativePolicyNotHardcodedDefault(){
        var t=resolved();
        assertThatThrownBy(()->service.close(c,t.id(),new Close(t.version(),null),"close")).isInstanceOf(ApiException.class).hasMessageContaining("DEFERRED_SOURCE");
        assertThat(service.detail(c,CUSTOMER,t.id()).status()).isEqualTo(SupportTicketStatus.resolved);
    }
    @Test void closedTicketOnlyAcceptsSameFacilityFollowUpWithoutCloningInternalData(){
        var t=resolved();close.available=true;var closed=service.close(c,t.id(),new Close(t.version(),"Satisfied"),"close");
        assertThat(closed.status()).isEqualTo(SupportTicketStatus.closed);
        assertThatThrownBy(()->service.customerMessage(c,t.id(),new CustomerMessage("Reply",List.of(),null),"closed-reply")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.reopen(c,t.id(),new Reopen("Reopen",closed.version()),"closed-reopen")).isInstanceOf(ApiException.class);
        var follow=service.followUp(c,t.id(),new Create("Follow-up","New issue",null,null,List.of()),"follow");
        assertThat(follow.parentTicketId()).isEqualTo(t.id());assertThat(follow.facilityId()).isEqualTo(facility.getId());assertThat(follow.status()).isEqualTo(SupportTicketStatus.open);
        assertThat(service.messages(c,CUSTOMER,follow.id(),query,"test").data()).extracting(SupportResponse.Message::body).containsExactly("New issue");
        assertThatThrownBy(()->service.followUp(c,t.id(),new Create("Wrong","Issue",otherFacility.getId(),null,List.of()),"wrong-follow")).isInstanceOf(ApiException.class);
    }
    @Test void autoCloseRechecksSevenDayDeadlineAndNeverClosesWaitingCustomer(){
        var t=resolved();close.available=true;notice.available=true;
        clock.time=NOW.plus(Duration.ofDays(7)).minusSeconds(1);assertThat(service.autoClose(t.id())).isFalse();
        clock.time=NOW.plus(Duration.ofDays(7));assertThat(service.autoClose(t.id())).isTrue();assertThat(service.autoClose(t.id())).isFalse();
        verify(notifications).createNotification(eq(customer.getId()),eq(NotificationType.SUPPORT),eq("Yêu cầu hỗ trợ đã tự động đóng sau thời gian xác nhận"),any(),eq(t.id()));
        verify(notifications).createNotification(eq(staff.getId()),eq(NotificationType.SUPPORT),eq("Yêu cầu hỗ trợ đã tự động đóng"),any(),eq(t.id()));
        verify(notifications).createNotification(eq(manager.getId()),eq(NotificationType.SUPPORT),eq("Yêu cầu hỗ trợ đã tự động đóng"),any(),eq(t.id()));
        var another=active();var waiting=service.requestInformation(s,another.id(),new Information("Need details",List.of(),another.version()),"ask");
        clock.time=clock.time.plus(Duration.ofDays(30));assertThat(service.autoClose(waiting.id())).isFalse();
    }
    @Test void autoCloseCannotUseQueuedNotificationAsVerifiedReviewNotice(){
        var t=resolved();close.available=true;clock.time=NOW.plus(Duration.ofDays(7));reset(notifications);
        assertThatThrownBy(()->service.autoClose(t.id())).isInstanceOf(ApiException.class).hasMessageContaining("DEFERRED_SOURCE");
        var current=service.detail(c,CUSTOMER,t.id());assertThat(current.status()).isEqualTo(SupportTicketStatus.resolved);assertThat(current.closedAt()).isNull();
        verifyNoInteractions(notifications);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"MISSING","NON_ATOMIC","NO_REFERENCE","WRONG_TICKET","WRONG_CUSTOMER","WRONG_RESOLUTION","WRONG_POLICY","WRONG_VERSION","NO_TIME","FUTURE","BEFORE_RESOLUTION"})
    void autoCloseRejectsMissingOrUnboundNotificationProofWithoutSideEffects(String mode){
        var t=resolved();close.available=true;notice.available=true;notice.mode=mode;clock.time=NOW.plus(Duration.ofDays(7));reset(notifications);
        long events=count("SupportEvent");
        assertThatThrownBy(()->service.autoClose(t.id())).isInstanceOf(ApiException.class);
        var current=service.detail(c,CUSTOMER,t.id());assertThat(current.status()).isEqualTo(SupportTicketStatus.resolved);
        assertThat(current.closedAt()).isNull();assertThat(count("SupportEvent")).isEqualTo(events);verifyNoInteractions(notifications);
    }
    @Test void autoCloseDoesNotReuseDeliveryProofFromPreviousResolution(){
        var t=resolved();close.available=true;notice.available=true;
        var oldEvent=em.createQuery("select e from SupportEvent e where e.ticket.id=:id and e.type='RESOLVED'",SupportEvent.class).setParameter("id",t.id()).getSingleResult();
        notice.supplied=new ReviewNotice(UUID.randomUUID(),t.id(),customer.getId(),oldEvent.getId(),"test-only-policy","v1",NOW);
        clock.time=NOW.plusSeconds(1);var reopened=service.reopen(c,t.id(),new Reopen("Still broken",t.version()),"reopen-for-notice");
        service.resolve(s,t.id(),new Resolution("New answer",List.of(),reopened.version()),"resolve-again");
        clock.time=clock.time.plus(Duration.ofDays(7));reset(notifications);
        assertThatThrownBy(()->service.autoClose(t.id())).isInstanceOf(ApiException.class).hasMessageContaining("proof is inconsistent");
        assertThat(service.detail(c,CUSTOMER,t.id()).status()).isEqualTo(SupportTicketStatus.resolved);verifyNoInteractions(notifications);
    }
    @Test void autoCloseAdapterErrorLeavesResolutionUntouched(){
        var t=resolved();close.available=true;notice.available=true;notice.mode="ERROR";clock.time=NOW.plus(Duration.ofDays(7));reset(notifications);
        assertThatThrownBy(()->service.autoClose(t.id())).isInstanceOf(IllegalStateException.class);
        assertThat(service.detail(c,CUSTOMER,t.id()).closedAt()).isNull();verifyNoInteractions(notifications);
    }
    @Test void invalidShortAutoClosePolicyIsRejected(){
        var t=resolved();close.available=true;close.days=1;clock.time=NOW.plus(Duration.ofDays(8));
        assertThatThrownBy(()->service.autoClose(t.id())).isInstanceOf(ApiException.class).hasMessageContaining("review period");
    }
    @Test void reopenWinsAgainstLaterAutoCloseAttempt(){
        var t=resolved();close.available=true;clock.time=NOW.plus(Duration.ofDays(7));
        service.reopen(c,t.id(),new Reopen("Not solved",t.version()),"reopen");assertThat(service.autoClose(t.id())).isFalse();
    }



    @Test void unsupportedEvidenceBlocksWithoutBypassingSharedFileOwnership(){
        assertThatThrownBy(()->service.create(c,new Create("Files","Help",facility.getId(),null,List.of(UUID.randomUUID())),"files")).isInstanceOf(ApiException.class).hasMessageContaining("DEFERRED_SOURCE");
    }
    @Test void cachedMessageRetryRechecksCurrentEvidenceReadAvailability(){
        var t=create();var ticket=em.find(SupportTicket.class,t.id());var file=UUID.randomUUID();
        var message=new SupportMessage(ticket,c.userId(),"CUSTOMER","PUBLIC","Historical attachment",receipts.json(List.of(file)),NOW);em.persist(message);em.flush();
        var body=new CustomerMessage("Historical attachment",List.of(file),null);
        var historical=new SupportResponse.Message(message.getId(),t.id(),c.userId(),"CUSTOMER","PUBLIC",body.body(),NOW,"COMPLETE",List.of(file));
        receipts.remember(c.userId(),"support_customer_message","historical",Arrays.asList(t.id(),body),historical);
        var replay=service.customerMessage(c,t.id(),body,"historical");
        assertThat(replay.evidenceCompleteness()).isEqualTo("UNKNOWN");assertThat(replay.evidenceFileIds()).isNull();
    }
    @Test void ownedRentalDerivesFacilityButDoesNotAllowConflictingFacility(){
        var rental=rentalFixture();
        var t=service.create(c,new Create("Rental issue","Please check",null,new Link(LinkType.RENTAL,rental.getId()),List.of()),"rental-link");
        assertThat(t.facilityId()).isEqualTo(facility.getId());assertThat(t.linkedId()).isEqualTo(rental.getId());
        assertThatThrownBy(()->service.create(c,new Create("Conflict","Issue",otherFacility.getId(),new Link(LinkType.RENTAL,rental.getId()),List.of()),"conflicting-link")).isInstanceOf(ApiException.class).hasMessageContaining("conflicts");
        assertThatThrownBy(()->service.create(actor(otherCustomer,RoleCode.CUSTOMER,null),new Create("Foreign","Issue",null,new Link(LinkType.RENTAL,rental.getId()),List.of()),"foreign-link")).isInstanceOf(ApiException.class);
    }
    @Test void unitLinkRequiresRealCustomerRentalAssociation(){
        var rental=rentalFixture();
        var t=service.create(c,new Create("Unit issue","Please check",null,new Link(LinkType.STORAGE_UNIT,rental.getStorageUnit().getId()),List.of()),"unit-link");
        assertThat(t.facilityId()).isEqualTo(facility.getId());
        assertThatThrownBy(()->service.create(actor(otherCustomer,RoleCode.CUSTOMER,null),new Create("Foreign unit","Issue",facility.getId(),new Link(LinkType.STORAGE_UNIT,rental.getStorageUnit().getId()),List.of()),"foreign-unit")).isInstanceOf(ApiException.class);
    }
    @Test void creationNotifiesCustomerAndScopedStaffAndManagerOnce(){
        var body=new Create("Cần hỗ trợ","Nội dung yêu cầu",facility.getId(),null,List.of());
        var t=service.create(c,body,"create-notices");service.create(c,body,"create-notices");
        verify(notifications).createNotification(eq(customer.getId()),eq(NotificationType.SUPPORT),eq("Đã ghi nhận yêu cầu hỗ trợ của bạn"),eq("Yêu cầu hỗ trợ: Cần hỗ trợ"),eq(t.id()));
        for(var user:List.of(staff,otherStaff,manager))verify(notifications).createNotification(eq(user.getId()),eq(NotificationType.SUPPORT),eq("Có yêu cầu hỗ trợ mới"),any(),eq(t.id()));
        verify(notifications,never()).createNotification(eq(foreignStaff.getId()),any(),any(),any(),any());
        verifyNoMoreInteractions(notifications);
    }
    @Test void customerConfirmationNotifiesAssignedStaffAndManagerAndCustomerOnce(){
        var t=resolved();close.available=true;reset(notifications);
        var body=new Close(t.version(),"Đã nhận kết quả");
        service.close(c,t.id(),body,"close-notices");service.close(c,t.id(),body,"close-notices");
        for(var user:List.of(staff,manager))verify(notifications).createNotification(eq(user.getId()),eq(NotificationType.SUPPORT),eq("Khách hàng đã xác nhận kết quả hỗ trợ"),any(),eq(t.id()));
        verify(notifications).createNotification(eq(customer.getId()),eq(NotificationType.SUPPORT),eq("Yêu cầu hỗ trợ của bạn đã được đóng"),any(),eq(t.id()));
        verifyNoMoreInteractions(notifications);
    }
    @Test void reopenedUnassignedTicketNotifiesEligibleQueueWithoutLeakingToOldOrForeignStaff(){
        var t=resolved();staff.setStatus(UserStatus.INACTIVE);em.flush();reset(notifications);
        service.reopen(c,t.id(),new Reopen("Cần hỗ trợ thêm",t.version()),"reopen-notices");
        verify(notifications).createNotification(eq(otherStaff.getId()),eq(NotificationType.SUPPORT),eq("Có yêu cầu hỗ trợ cần tiếp nhận lại"),any(),eq(t.id()));
        verify(notifications).createNotification(eq(manager.getId()),eq(NotificationType.SUPPORT),eq("Khách hàng yêu cầu xử lý lại hỗ trợ"),any(),eq(t.id()));
        verify(notifications).createNotification(eq(customer.getId()),eq(NotificationType.SUPPORT),eq("Đã ghi nhận yêu cầu xử lý lại của bạn"),any(),eq(t.id()));
        verifyNoMoreInteractions(notifications);
    }
    @Test void resolutionRejectsEmptyDescriptionWithoutEvidence(){
        var t=active();reset(notifications);
        var messages=count("SupportMessage");
        assertThatThrownBy(()->service.resolve(s,t.id(),new Resolution("   ",List.of(),t.version()),"empty-resolution"))
            .isInstanceOf(ApiException.class).hasMessageContaining("minh chứng");
        assertThat(service.detail(c,CUSTOMER,t.id()).status()).isEqualTo(SupportTicketStatus.in_progress);
        assertThat(count("SupportMessage")).isEqualTo(messages);
        verifyNoInteractions(notifications);
    }
    @Test void resolutionWithEvidenceOnlyPublishesProofForCustomerReview(){
        var t=active();var proof=List.of(UUID.randomUUID());
        when(evidence.atomic()).thenReturn(true);when(evidence.supportsFiles(proof)).thenReturn(true);
        var body=new Resolution("",proof,t.version());
        var resolved=service.resolve(s,t.id(),body,"evidence-resolution");
        assertThat(resolved.status()).isEqualTo(SupportTicketStatus.resolved);
        assertThat(service.messages(c,CUSTOMER,t.id(),query,"test").data()).anySatisfy(message->{
            assertThat(message.visibility()).isEqualTo("PUBLIC");
            assertThat(message.body()).contains("minh chứng xử lý");
            assertThat(message.evidenceFileIds()).isEqualTo(proof);
        });
        service.resolve(s,t.id(),body,"evidence-resolution");
        verify(evidence,times(1)).requireAttach(eq(s),any(),eq(Visibility.PUBLIC),eq(proof));
    }
    @Test void resolutionCannotUseUnreadableOrInternalEvidence(){
        var t=active();var proof=List.of(UUID.randomUUID());
        when(evidence.atomic()).thenReturn(true);
        doThrow(com.storagehub.common.api.ApiExceptions.notFound("Evidence not available for Customer"))
            .when(evidence).requireAttach(eq(s),any(),eq(Visibility.PUBLIC),eq(proof));
        assertThatThrownBy(()->service.resolve(s,t.id(),new Resolution("",proof,t.version()),"invalid-proof"))
            .isInstanceOf(ApiException.class).hasMessageContaining("Evidence");
        assertThat(service.detail(c,CUSTOMER,t.id()).status()).isEqualTo(SupportTicketStatus.in_progress);
    }
    @Test void linkedTicketResolutionUpdatesSupportForCustomerReviewWithoutChangingRental(){
        var rental=rentalFixture();var originalStatus=rental.getStatus();var originalEnd=rental.getContractEndDate();
        var t=service.create(c,new Create("Rental issue","Please check",null,new Link(LinkType.RENTAL,rental.getId()),List.of()),"linked");
        var accepted=service.claim(s,t.id(),new SupportCommands.Version(t.version()),"claim-linked");
        var body=new Resolution("Rental question answered",List.of(),accepted.version());
        var resolved=service.resolve(s,accepted.id(),body,"support-done");
        assertThat(resolved.status()).isEqualTo(SupportTicketStatus.resolved);
        assertThat(resolved.version()).isGreaterThan(accepted.version());
        assertThat(resolved.resolvedBy()).isEqualTo(staff.getId());
        for(var audience:List.of(CUSTOMER,STAFF,MANAGER)){
            var actor=audience==CUSTOMER?c:audience==STAFF?s:m;
            assertThat(service.detail(actor,audience,t.id()).status()).isEqualTo(SupportTicketStatus.resolved);
            assertThat(service.list(actor,audience,query,"test").data()).anyMatch(row->row.id().equals(t.id())&&row.status()==SupportTicketStatus.resolved);
        }
        assertThat(service.messages(c,CUSTOMER,t.id(),query,"test").data()).anyMatch(row->row.body().equals(body.summary()));
        var messageCount=count("SupportMessage");
        assertThat(service.resolve(s,accepted.id(),body,"support-done").version()).isEqualTo(resolved.version());
        assertThat(count("SupportMessage")).isEqualTo(messageCount);
        close.available=true;
        var closed=service.close(c,t.id(),new Close(resolved.version(),"Confirmed"),"confirm-linked");
        assertThat(closed.status()).isEqualTo(SupportTicketStatus.closed);
        assertThat(rental.getStatus()).isEqualTo(originalStatus);assertThat(rental.getContractEndDate()).isEqualTo(originalEnd);
    }
    @Test @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void independentTransactionsRollbackFailuresAndSerializeConcurrentRetriesAndAssignments() throws Exception {
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(()->service.create(c,new Create("Files","Help",facility.getId(),null,List.of(UUID.randomUUID())),"failed-files")).isInstanceOf(ApiException.class);
        tx.executeWithoutResult(st->{assertThat(count("SupportTicket")).isZero();assertThat(count("SupportState")).isZero();assertThat(count("SupportMessage")).isZero();assertThat(count("SupportCommandReceipt")).isZero();});
        var body=new Create("Concurrent","Same command",facility.getId(),null,List.of());
        var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var a=pool.submit(()->{start.await();return service.create(c,body,"concurrent-create");});
            var b=pool.submit(()->{start.await();return service.create(c,body,"concurrent-create");});start.countDown();
            var created=a.get(30,java.util.concurrent.TimeUnit.SECONDS);assertThat(b.get(30,java.util.concurrent.TimeUnit.SECONDS).id()).isEqualTo(created.id());
            tx.executeWithoutResult(st->{assertThat(count("SupportTicket")).isEqualTo(1);assertThat(count("SupportMessage")).isEqualTo(1);});
            var assignmentStart=new java.util.concurrent.CountDownLatch(1);var successes=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(UUID staffId:List.of(staff.getId(),otherStaff.getId()))successes.add(pool.submit(()->{
                assignmentStart.await();try{service.claim(staffId.equals(staff.getId())?s:s2,created.id(),new SupportCommands.Version(created.version()),staffId.toString());return true;}
                catch(ApiException e){assertThat(e.getStatus().value()).isEqualTo(409);return false;}
            }));
            assignmentStart.countDown();int assigned=0;for(var result:successes)if(result.get(30,java.util.concurrent.TimeUnit.SECONDS))assigned++;
            assertThat(assigned).isEqualTo(1);var current=service.detail(c,CUSTOMER,created.id());assertThat(current.assignmentRevision()).isEqualTo(1);
            var assignedActor=current.assignedStaffId().equals(staff.getId())?s:s2;
            var accepted=current;
            doThrow(new IllegalStateException("Test-only notification failure")).when(notifications).createNotification(eq(customer.getId()),eq(NotificationType.SUPPORT),anyString(),anyString(),eq(created.id()));
            assertThatThrownBy(()->service.resolve(assignedActor,created.id(),new Resolution("Answer",List.of(),accepted.version()),"failed-resolution")).isInstanceOf(IllegalStateException.class);
            var unchanged=service.detail(c,CUSTOMER,created.id());assertThat(unchanged.status()).isEqualTo(SupportTicketStatus.in_progress);assertThat(unchanged.resolvedAt()).isNull();
            tx.executeWithoutResult(st->{assertThat(count("SupportMessage")).isEqualTo(1);assertThat(em.createQuery("select count(r) from SupportCommandReceipt r where r.operation='support_resolve'",Long.class).getSingleResult()).isZero();});
            reset(notifications);var resolved=service.resolve(assignedActor,created.id(),new Resolution("Answer",List.of(),accepted.version()),"successful-resolution");
            close.available=true;notice.available=true;clock.time=NOW.plus(Duration.ofDays(7));var race=new java.util.concurrent.CountDownLatch(1);
            var closed=pool.submit(()->{race.await();return service.autoClose(created.id());});
            var reopened=pool.submit(()->{race.await();try{service.reopen(c,created.id(),new Reopen("Still broken",resolved.version()),"race-reopen");return true;}catch(ApiException e){assertThat(e.getStatus().value()).isEqualTo(409);return false;}});
            race.countDown();var closeWon=closed.get(30,java.util.concurrent.TimeUnit.SECONDS);var reopenWon=reopened.get(30,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(closeWon).isNotEqualTo(reopenWon);
            var terminal=service.detail(c,CUSTOMER,created.id());assertThat(terminal.status()).isEqualTo(closeWon?SupportTicketStatus.closed:SupportTicketStatus.in_progress);
            assertThat(terminal.closedAt()==null).isEqualTo(reopenWon);
        }finally{pool.shutdownNow();}
    }
    @Test void queryRejectsOverflowUnknownFieldsAndRepeatedParameters(){
        for(var entry:Map.of("page","2147483647","size","101","priority","HIGH","sort","description,asc").entrySet()){
            var q=new LinkedMultiValueMap<String,String>();q.add(entry.getKey(),entry.getValue());
            assertThatThrownBy(()->SupportQuery.parse(q,false,false)).isInstanceOf(ApiException.class);
        }
        var q=new LinkedMultiValueMap<String,String>();q.add("status","open");q.add("status","closed");
        assertThatThrownBy(()->SupportQuery.parse(q,true,false)).isInstanceOf(ApiException.class);
    }
    @Test void searchEscapesSqlWildcardsAndPaginationKeepsScopedTotals(){
        service.create(c,new Create("100% question","Literal wildcard",facility.getId(),null,List.of()),"percent");create();
        var params=new LinkedMultiValueMap<String,String>();params.add("search","%");params.add("size","1");
        var result=service.list(c,CUSTOMER,SupportQuery.parse(params,false,false),"test");
        assertThat(result.pagination().totalItems()).isEqualTo(1);assertThat(result.data()).extracting(SupportResponse::subject).containsExactly("100% question");
    }
    @Test void recentActivitySortIncludesCustomerReplyWithoutRewritingSharedTicket(){
        var older=active();clock.time=NOW.plusSeconds(60);var newer=active();clock.time=NOW.plusSeconds(120);
        em.flush();em.clear();
        var sharedUpdatedAt=em.find(SupportTicket.class,older.id()).getUpdatedAt();
        service.customerMessage(c,older.id(),new CustomerMessage("New information",List.of(),null),"recent-customer");
        assertRecentActivityOrder(older.id(),newer.id());
        assertThat(em.find(SupportTicket.class,older.id()).getUpdatedAt()).isEqualTo(sharedUpdatedAt);
        assertThat(em.find(SupportState.class,older.id()).getChangedAt()).isEqualTo(clock.time);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(Visibility.class)
    void recentActivitySortIncludesStaffPublicReplyAndInternalNote(Visibility visibility){
        var older=active();clock.time=NOW.plusSeconds(60);var newer=active();clock.time=NOW.plusSeconds(120);
        em.flush();em.clear();
        var sharedUpdatedAt=em.find(SupportTicket.class,older.id()).getUpdatedAt();
        service.staffMessage(s,older.id(),new StaffMessage("Progress",List.of(),visibility),"recent-staff");
        assertRecentActivityOrder(older.id(),newer.id());
        assertThat(em.find(SupportTicket.class,older.id()).getUpdatedAt()).isEqualTo(sharedUpdatedAt);
        assertThat(em.find(SupportState.class,older.id()).getChangedAt()).isEqualTo(clock.time);
    }

    @Test void recentActivitySortKeepsLegacyRowsWithoutInventingWorkflowMetadata(){
        var legacy=new SupportTicket();legacy.setCustomer(customer);legacy.setFacility(facility);legacy.setSubject("Legacy");legacy.setDescription("Read only");em.persist(legacy);em.flush();
        clock.time=legacy.getUpdatedAt().plusSeconds(60);var current=create();
        assertRecentActivityOrder(current.id(),legacy.getId());
        assertThat(em.find(SupportState.class,legacy.getId())).isNull();
    }
    private void assertRecentActivityOrder(UUID latest,UUID earlier){
        for(var audience:List.of(CUSTOMER,MANAGER)){
            var actor=audience==CUSTOMER?c:m;
            for(String direction:List.of("asc","desc")){
                var filters=new LinkedMultiValueMap<String,String>();filters.add("sort","updatedAt,"+direction);filters.add("size","1");
                var first=service.list(actor,audience,SupportQuery.parse(filters,audience==MANAGER,false),"test");
                assertThat(first.pagination().totalItems()).isEqualTo(2);
                assertThat(first.data()).extracting(SupportResponse::id).containsExactly(direction.equals("desc")?latest:earlier);
                filters.add("page","1");
                var second=service.list(actor,audience,SupportQuery.parse(filters,audience==MANAGER,false),"test");
                assertThat(second.data()).extracting(SupportResponse::id).containsExactly(direction.equals("desc")?earlier:latest);
            }
        }
    }
    @Test void httpRejectsInjectedPriorityVisibilityAndMissingKeyBeforeMutation() throws Exception {
        var actors=mock(ActorContext.class);when(actors.required()).thenReturn(c);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new CustomerSupportController(actors,service))
            .setControllerAdvice(new SupportRequestAdvice(new ObjectMapper()),new com.storagehub.common.api.GlobalExceptionHandler()).build();
        var path="/api/customer/support-workflows";
        var json="{\"subject\":\"Question\",\"description\":\"Help\",\"facilityId\":\""+facility.getId()+"\"}";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content(json))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key","inject").contentType("application/json").content(json.substring(0,json.length()-1)+",\"priority\":\"HIGH\",\"visibility\":\"INTERNAL\"}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        for(String invalid:List.of(json+" {}",json.substring(0,json.length()-1)+",\"subject\":\"Duplicate\"}","[]"))
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key","invalid").contentType("application/json").content(invalid))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key","valid").contentType("application/json").content(json))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("data.slaCompleteness").value("UNKNOWN"));
        assertThat(count("SupportTicket")).isEqualTo(1);
    }

    /** Fixture only: pre-policy persisted assignment, never a runtime Manager command. */
    private SupportResponse legacyAssignment(SupportResponse t,User assignee) {
        em.find(SupportTicket.class,t.id()).setAssignedTo(assignee);
        em.find(SupportState.class,t.id()).assign(NOW);em.flush();
        return service.detail(c,CUSTOMER,t.id());
    }
    @Test void managerReadOnlyAndNewEscalationsAreDisabledBeforeAnyWrite() {
        var t=create();long events=count("SupportEvent"),receiptsBefore=count("SupportCommandReceipt");
        assertThatThrownBy(()->service.assign(m,t.id(),new Assignment(staff.getId(),t.version(),"Assign"),"blocked")).hasMessageContaining("read-only");
        assertThatThrownBy(()->service.decideEscalation(m,t.id(),UUID.randomUUID(),new EscalationDecision(Decision.ROUTE,"Route",t.version()),"blocked-route")).hasMessageContaining("read-only");
        assertThatThrownBy(()->service.escalate(s,t.id(),new Escalate(SupportCommands.Module.PAYMENT,"Route",List.of(),t.version()),"blocked-escalation")).hasMessageContaining("disabled");
        assertThat(count("SupportEvent")).isEqualTo(events);assertThat(count("SupportCommandReceipt")).isEqualTo(receiptsBefore);
        assertThat(count("SupportEscalation")).isZero();assertThat(service.detail(m,MANAGER,t.id()).assignedStaffId()).isNull();
    }
    @Test void managerMonitoringRequiresOnlyReadPermissionAndReadScope() {
        var t=active();var view=em.createQuery("select p from Permission p where p.code=:code",Permission.class)
            .setParameter("code",SystemPermission.VIEW_SUPPORT.code()).getSingleResult();
        manager.getRoles().forEach(r->r.setPermissions(new HashSet<>(Set.of(view))));
        em.createQuery("update UserFacilityScope f set f.scopeLevel=:level where f.user.id=:id")
            .setParameter("level",FacilityScopeLevel.READ).setParameter("id",manager.getId()).executeUpdate();em.flush();em.clear();
        var readActor=new ActorPrincipal(manager.getId(),UUID.randomUUID(),Set.of(RoleCode.MANAGER),Set.of(SystemPermission.VIEW_SUPPORT.code()),Map.of(facility.getId(),FacilityScopeLevel.READ));
        assertThat(service.detail(readActor,MANAGER,t.id()).id()).isEqualTo(t.id());
        assertThat(service.summary(readActor,facility.getId()).unassignedOpen()).isZero();
    }
    @Test void managerWithStaffRoleCannotClaimThroughTheStaffEndpoint() {
        var t=create();
        var both=new ActorPrincipal(m.userId(),UUID.randomUUID(),Set.of(RoleCode.MANAGER,RoleCode.STAFF),
            m.permissions(),m.facilityScopes());
        assertThatThrownBy(()->service.claim(both,t.id(),new SupportCommands.Version(t.version()),"manager-as-staff"))
            .hasMessageContaining("read-only");
        assertThat(service.detail(c,CUSTOMER,t.id()).assignedStaffId()).isNull();
    }
    @Test void historicalUnresolvedEscalationsAreNotSilentlyIgnoredByNewPolicy() {
        var t=active();var ticket=em.find(SupportTicket.class,t.id());
        em.persist(new SupportEscalation(ticket,SupportCommands.Module.PAYMENT.name(),"Historical owner review",staff.getId(),"[]",NOW));em.flush();
        assertThatThrownBy(()->service.resolve(s,t.id(),new Resolution("Done",List.of(),t.version()),"unverified-history"))
            .hasMessageContaining("Trusted escalation result unavailable");
        assertThat(service.detail(c,CUSTOMER,t.id()).status()).isEqualTo(SupportTicketStatus.in_progress);
    }
    private SupportResponse create(){return service.create(c,new Create("Question","Help",facility.getId(),null,List.of()),UUID.randomUUID().toString());}
    private SupportResponse assigned(){return legacyAssignment(create(),staff);}
    private SupportResponse active(){var t=create();return service.claim(s,t.id(),new SupportCommands.Version(t.version()),UUID.randomUUID().toString());}
    private SupportResponse resolved(){var t=active();return service.resolve(s,t.id(),new Resolution("Answered",List.of(),t.version()),UUID.randomUUID().toString());}
    private Rental rentalFixture(){
        var one=java.math.BigDecimal.ONE;var zero=java.math.BigDecimal.ZERO;
        var type=new UnitType();type.setFacility(facility);type.setCode("TEST");type.setName("Test-only");type.setLengthM(one);type.setWidthM(one);type.setHeightM(one);type.setMonthlyPrice(one);type.setMaxLoadKg(one);type.setRackLengthM(one);type.setRackWidthM(one);type.setRackHeightM(one);em.persist(type);
        var unit=new StorageUnit();unit.setFacility(facility);unit.setUnitType(type);unit.setCode("TEST-UNIT");em.persist(unit);
        var quote=new ReservationQuote();quote.setCustomer(customer);quote.setFacility(facility);quote.setUnitType(type);quote.setPricingPackageCode("TEST");quote.setPolicyVersion("test-only");quote.setStartDate(LocalDate.of(2026,10,1));quote.setEndDate(LocalDate.of(2026,11,1));quote.setRentalMonths(1);quote.setMonthlyPrice(one);quote.setSubtotal(one);quote.setDiscountRate(zero);quote.setDiscountAmount(zero);quote.setTotalAfterDiscount(one);quote.setReservationDepositAmount(zero);quote.setSecurityDepositAmount(zero);quote.setRemainingRentalAmount(one);quote.setDueAtCheckIn(one);quote.setTotalInitialObligation(one);quote.setQuotedAt(NOW);quote.setExpiresAt(NOW.plusSeconds(1800));em.persist(quote);
        var reservation=new Reservation();reservation.setReservationCode("TEST-RES");reservation.setCustomer(customer);reservation.setSourceQuote(quote);reservation.setIdempotencyKey("test");reservation.setFacility(facility);reservation.setUnitType(type);reservation.setStartDate(quote.getStartDate());reservation.setEndDate(quote.getEndDate());em.persist(reservation);
        var rental=new Rental();rental.setCustomer(customer);rental.setFacility(facility);rental.setStorageUnit(unit);rental.setReservation(reservation);rental.setStartDate(quote.getStartDate());rental.setContractEndDate(LocalDate.of(2026,10,31));rental.setMonthlyPrice(one);em.persist(rental);em.flush();return rental;
    }
    private long count(String entity){em.flush();return em.createQuery("select count(x) from "+entity+" x",Long.class).getSingleResult();}
    private Permission permission(SystemPermission code){var p=new Permission();p.setCode(code.code());p.setName(code.code());em.persist(p);return p;}
    private Role role(RoleCode code,Set<Permission> permissions){var r=new Role();r.setCode(code);r.setName(code.name());r.setPermissions(new HashSet<>(permissions));em.persist(r);return r;}
    private User user(String label,Role role){var u=new User();u.setEmail(label+"@support-test.invalid");u.setFullName(label);u.setPasswordHash("test-only-not-for-login");u.getRoles().add(role);em.persist(u);return u;}
    private Facility facility(String code){var f=new Facility();f.setCode(code);f.setName(code);f.setAddress("H2 test only");f.setCity("Test");em.persist(f);return f;}
    private void scope(User u,Facility f,FacilityScopeLevel level){var scope=new UserFacilityScope();scope.setUser(u);scope.setFacility(f);scope.setScopeLevel(level);em.persist(scope);}
    private ActorPrincipal actor(User u,RoleCode role,Facility f){return new ActorPrincipal(u.getId(),UUID.randomUUID(),Set.of(role),role==RoleCode.CUSTOMER?Set.of():Set.of(SystemPermission.VIEW_SUPPORT.code(),SystemPermission.MANAGE_SUPPORT.code()),f==null?Map.of():Map.of(f.getId(),role==RoleCode.MANAGER?FacilityScopeLevel.MANAGE:FacilityScopeLevel.OPERATE));}
}
