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
@Import({SupportService.class,SupportPersistence.class,SupportWorkflowIntegrationTests.Sources.class})
class SupportWorkflowIntegrationTests {
    static final Instant NOW=Instant.parse("2026-10-08T03:00:00Z");
    @TestConfiguration static class Sources {
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean AuditLogService audit(){return mock(AuditLogService.class);}
        @Bean NotificationService notifications(){return mock(NotificationService.class);}
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
    @Autowired TestClock clock;
    @Autowired TestClose close;
    @Autowired TestReceiver receiver;
    @Autowired TestNotice notice;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    Facility facility,otherFacility;
    User customer,otherCustomer,manager,staff,otherStaff,foreignStaff;
    ActorPrincipal c,m,s,s2;
    SupportQuery query=SupportQuery.parse(new LinkedMultiValueMap<>(),false,false);

    @BeforeEach void fixture(){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()){
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->fixture());return;
        }
        reset(notifications);clock.time=NOW;close.available=false;close.days=7;receiver.enabled=false;receiver.forged=false;receiver.status=null;receiver.routes=0;
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
    @Test void legacyNullFacilityIsNotImplicitlyTriagedOrBackfilled(){
        var legacy=new SupportTicket();legacy.setCustomer(customer);legacy.setSubject("Legacy");legacy.setDescription("Unscoped");em.persist(legacy);em.flush();
        assertThat(service.list(c,CUSTOMER,query,"test").pagination().totalItems()).isZero();
        assertThatThrownBy(()->service.detail(m,MANAGER,legacy.getId())).isInstanceOf(ApiException.class);
        assertThat(count("SupportState")).isZero();assertThat(legacy.getFacility()).isNull();
    }
    @Test void legacyScopedTicketIsReadOnlyUntilVerifiedMetadataExists(){
        var legacy=new SupportTicket();legacy.setCustomer(customer);legacy.setFacility(facility);legacy.setSubject("Legacy");legacy.setDescription("Needs migration");em.persist(legacy);em.flush();
        assertThat(service.detail(m,MANAGER,legacy.getId()).workflowReady()).isFalse();
        assertThatThrownBy(()->service.assign(m,legacy.getId(),new Assignment(staff.getId(),0L,"Assign"),"legacy")).isInstanceOf(ApiException.class).hasMessageContaining("DEFERRED_SOURCE");
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
    @Test void staffOptionsExcludeForeignStaffAndUseStableIds(){
        var options=service.staffOptions(m,facility.getId(),query,"test").data();
        assertThat(options).extracting(SupportResponse.StaffOption::id).containsExactlyInAnyOrder(staff.getId(),otherStaff.getId());
    }
    @Test void openRequiresStaffAcceptanceBeforeResolution(){
        var t=assigned();
        assertThatThrownBy(()->service.resolve(s,t.id(),new Resolution("Done",List.of(),t.version()),"too-early")).isInstanceOf(ApiException.class);
        var accepted=service.accept(s,t.id(),new SupportCommands.Version(t.version()),"accept");
        assertThat(accepted.status()).isEqualTo(SupportTicketStatus.in_progress);assertThat(accepted.acceptedAt()).isEqualTo(NOW);
        assertThat(service.accept(s,t.id(),new SupportCommands.Version(t.version()),"accept").version()).isEqualTo(accepted.version());
        assertThatThrownBy(()->service.accept(s,t.id(),new SupportCommands.Version(t.version()),"stale")).isInstanceOf(ApiException.class).hasMessageContaining("stale");
    }
    @Test void managerCannotImpersonateAssignedStaffToComplete(){
        var t=active();
        assertThatThrownBy(()->service.resolve(m,t.id(),new Resolution("Manager done",List.of(),t.version()),"manager-complete")).isInstanceOf(ApiException.class);
        assertThat(service.detail(c,CUSTOMER,t.id()).status()).isEqualTo(SupportTicketStatus.in_progress);
    }
    @Test void reassignResetsAcceptancePreservesHistoryAndRevokesOldAssignee(){
        var t=active();var reassigned=service.assign(m,t.id(),new Assignment(otherStaff.getId(),t.version(),"Shift change"),"reassign");
        assertThat(reassigned.status()).isEqualTo(SupportTicketStatus.open);assertThat(reassigned.acceptedAt()).isNull();
        assertThat(reassigned.assignmentRevision()).isEqualTo(2);
        assertThatThrownBy(()->service.staffMessage(s,t.id(),new StaffMessage("Old reply",List.of(),Visibility.PUBLIC),"old")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.accept(s,t.id(),new SupportCommands.Version(t.version()),"accept")).isInstanceOf(ApiException.class);
        assertThat(service.events(m,MANAGER,t.id(),query,"test").data()).extracting(SupportResponse.Event::type).contains("ACCEPTED","ASSIGNED");
        assertThat(service.accept(s2,t.id(),new SupportCommands.Version(reassigned.version()),"new-accept").status()).isEqualTo(SupportTicketStatus.in_progress);
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
        verifyNoInteractions(notifications);
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
    @Test void reopenWithIneligiblePreviousStaffReturnsToManagerQueue(){
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
    @Test void unavailableReceiverDoesNotCreatePermanentEscalation(){
        var t=active();
        assertThatThrownBy(()->service.escalate(s,t.id(),new Escalate(SupportCommands.Module.PAYMENT,"Need owner",List.of(),t.version()),"escalate")).isInstanceOf(ApiException.class).hasMessageContaining("DEFERRED_SOURCE");
        assertThat(count("SupportEscalation")).isZero();
    }
    @Test void routingIsNotReceiverAcknowledgementOrCompletion(){
        receiver.enabled=true;var t=active();var e=service.escalate(s,t.id(),new Escalate(SupportCommands.Module.PAYMENT,"Payment owner needed",List.of(),t.version()),"escalate");
        var current=service.detail(m,MANAGER,t.id());
        var routed=service.decideEscalation(m,t.id(),e.id(),new EscalationDecision(Decision.ROUTE,"Coordinate",current.version()),"route");
        assertThat(routed.status()).isEqualTo("ROUTED");assertThat(routed.receiverStatus()).isNull();assertThat(receiver.routes).isEqualTo(1);
        assertThatThrownBy(()->service.resolve(s,t.id(),new Resolution("Done",List.of(),service.detail(s,STAFF,t.id()).version()),"premature")).isInstanceOf(ApiException.class);
        receiver.status="ACKNOWLEDGED";
        assertThatThrownBy(()->service.resolve(s,t.id(),new Resolution("Done",List.of(),service.detail(s,STAFF,t.id()).version()),"ack-only")).isInstanceOf(ApiException.class);
        receiver.status="COMPLETED";receiver.forged=true;
        assertThatThrownBy(()->service.escalations(m,MANAGER,t.id(),query,"test")).isInstanceOf(ApiException.class).hasMessageContaining("inconsistent");
        receiver.forged=false;
        assertThat(service.resolve(s,t.id(),new Resolution("Owner result verified",List.of(),service.detail(s,STAFF,t.id()).version()),"real-result").status()).isEqualTo(SupportTicketStatus.resolved);
    }
    @Test void managerRejectDoesNotFabricateOwnerResultAndStaffRetainsResolutionOwnership(){
        receiver.enabled=true;var t=active();var e=service.escalate(s,t.id(),new Escalate(SupportCommands.Module.ACCOUNT,"Coordinate",List.of(),t.version()),"escalate");
        receiver.enabled=false;
        var rejected=service.decideEscalation(m,t.id(),e.id(),new EscalationDecision(Decision.REJECT,"Staff can answer",service.detail(m,MANAGER,t.id()).version()),"reject");
        assertThat(rejected.status()).isEqualTo("REJECTED");assertThat(rejected.resultRef()).isNull();assertThat(receiver.routes).isZero();
        assertThat(service.resolve(s,t.id(),new Resolution("Answered",List.of(),service.detail(s,STAFF,t.id()).version()),"resolve").resolvedBy()).isEqualTo(staff.getId());
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
    @Test void linkedTicketCannotResolveUsingNarrativeInsteadOfActualModuleOutcome(){
        var rental=rentalFixture();var originalStatus=rental.getStatus();var originalEnd=rental.getContractEndDate();
        var t=service.create(c,new Create("Rental issue","Please check",null,new Link(LinkType.RENTAL,rental.getId()),List.of()),"linked");
        t=service.assign(m,t.id(),new Assignment(staff.getId(),t.version(),"Process"),"assign-linked");
        var accepted=service.accept(s,t.id(),new SupportCommands.Version(t.version()),"accept-linked");
        assertThatThrownBy(()->service.resolve(s,accepted.id(),new Resolution("Refund pending",List.of(),accepted.version()),"pretend-done")).isInstanceOf(ApiException.class).hasMessageContaining("DEFERRED_SOURCE");
        assertThat(service.detail(c,CUSTOMER,t.id()).status()).isEqualTo(SupportTicketStatus.in_progress);
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
                assignmentStart.await();try{service.assign(m,created.id(),new Assignment(staffId,created.version(),"Concurrent allocation"),staffId.toString());return true;}
                catch(ApiException e){assertThat(e.getStatus().value()).isEqualTo(409);return false;}
            }));
            assignmentStart.countDown();int assigned=0;for(var result:successes)if(result.get(30,java.util.concurrent.TimeUnit.SECONDS))assigned++;
            assertThat(assigned).isEqualTo(1);var current=service.detail(c,CUSTOMER,created.id());assertThat(current.assignmentRevision()).isEqualTo(1);
            var assignedActor=current.assignedStaffId().equals(staff.getId())?s:s2;
            var accepted=service.accept(assignedActor,created.id(),new SupportCommands.Version(current.version()),"accepted");
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
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(Decision.class)
    void recentActivitySortIncludesEscalationDecisionWithoutRewritingSharedTicket(Decision decision){
        receiver.enabled=true;var older=active();
        var escalation=service.escalate(s,older.id(),new Escalate(SupportCommands.Module.PAYMENT,"Owner review",List.of(),older.version()),"recent-escalate");
        clock.time=NOW.plusSeconds(60);var newer=active();clock.time=NOW.plusSeconds(120);
        em.flush();em.clear();
        var sharedUpdatedAt=em.find(SupportTicket.class,older.id()).getUpdatedAt();
        service.decideEscalation(m,older.id(),escalation.id(),new EscalationDecision(decision,"Coordinate",service.detail(m,MANAGER,older.id()).version()),"recent-decision");
        assertRecentActivityOrder(older.id(),newer.id());
        assertThat(em.find(SupportTicket.class,older.id()).getUpdatedAt()).isEqualTo(sharedUpdatedAt);
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
        var path="/api/customer/support-tickets";
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

    private SupportResponse create(){return service.create(c,new Create("Question","Help",facility.getId(),null,List.of()),UUID.randomUUID().toString());}
    private SupportResponse assigned(){var t=create();return service.assign(m,t.id(),new Assignment(staff.getId(),t.version(),"Assigned by ID"),UUID.randomUUID().toString());}
    private SupportResponse active(){var t=assigned();return service.accept(s,t.id(),new SupportCommands.Version(t.version()),UUID.randomUUID().toString());}
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
