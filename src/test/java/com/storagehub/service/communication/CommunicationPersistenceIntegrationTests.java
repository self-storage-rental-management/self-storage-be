package com.storagehub.service.communication;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.api.support.SupportCommands;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.support.*;
import com.storagehub.service.ledger.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Every fixture is committed to an isolated H2 database before the independent delivery transaction. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1",
    "spring.datasource.url=jdbc:h2:mem:duong-communication-integration;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:rental-ledger-test-schema.sql,classpath:notification-outbox-test-schema.sql",
    "storagehub.integration.ledger.enabled=true",
    "storagehub.integration.communication.enabled=true",
    "storagehub.integration.communication.worker-initial-delay-ms=86400000",
    "app.bootstrap-admin.enabled=false"
})
@ActiveProfiles("test") @Import(CommunicationPersistenceIntegrationTests.Config.class)
class CommunicationPersistenceIntegrationTests {
    static class MovingClock extends Clock {
        final AtomicReference<Instant> at=new AtomicReference<>(Instant.now().plusSeconds(10));
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId z){return this;}
        public Instant instant(){return at.getAndUpdate(i->i.plusNanos(1000));}
        void advance(Duration d){at.updateAndGet(i->i.plus(d));}
    }
    @TestConfiguration static class Config {@Bean @Primary MovingClock clock(){return new MovingClock();}}
    @Autowired EntityManager em; @Autowired PlatformTransactionManager transactions;
    @Autowired MovingClock clock; @Autowired NotificationOutbox outbox;
    @Autowired NotificationDeliveryService delivery; @Autowired CommunicationPolicyService policies;
    @Autowired CommunicationSourceAdapters source; @Autowired SupportService support;
    @Autowired LedgerStore ledger; @Autowired LedgerQueryService ledgerQueries;
    @Autowired Environment environment; @Autowired ObjectMapper mapper;
    @Autowired com.storagehub.service.integration.DuongPolicyReadbackService readback;
    @Autowired com.storagehub.service.renewal.integration.RenewalPolicyPublicationService renewalPolicies;
    TransactionTemplate tx; UUID facility,customer,other,staff,ticket; ActorPrincipal customerActor,otherActor,staffActor,businessActor;
    long version;
    @BeforeEach void fixture(){
        clock.at.set(Instant.now().plusSeconds(10)); // Each test starts independently of auto-close's advanced clock.
        tx=new TransactionTemplate(transactions);
        tx.executeWithoutResult(s->{
            var f=new Facility();f.setCode(UUID.randomUUID().toString().substring(0,8));f.setName("ISOLATED TEST");f.setAddress("TEST");f.setCity("TEST");em.persist(f);facility=f.getId();
            var c=user(RoleCode.CUSTOMER);customer=c.getId();customerActor=actor(c,RoleCode.CUSTOMER);
            var o=user(RoleCode.CUSTOMER);other=o.getId();otherActor=actor(o,RoleCode.CUSTOMER);
            var st=user(RoleCode.STAFF);staff=st.getId();var scope=new UserFacilityScope();scope.setUser(st);scope.setFacility(f);scope.setScopeLevel(FacilityScopeLevel.OPERATE);em.persist(scope);staffActor=actor(st,RoleCode.STAFF);
            var b=user(RoleCode.BUSINESS);businessActor=actor(b,RoleCode.BUSINESS);
            var t=new SupportTicket();t.setCustomer(c);t.setFacility(f);t.setAssignedTo(st);t.setSubject("Kiểm thử hỗ trợ");t.setDescription("Hồ sơ chỉ tồn tại trong H2 test");t.setStatus(SupportTicketStatus.in_progress);em.persist(t);ticket=t.getId();
            var state=new SupportState(t,null,null,null,clock.instant());state.assign(clock.instant());state.accept(staff,clock.instant());em.persist(state);em.flush();version=state.getVersion();
        });
    }
    private User user(RoleCode code){
        var roles=em.createQuery("select r from Role r where r.code=:c",Role.class).setParameter("c",code).getResultList();
        Role r;if(roles.isEmpty()){r=new Role();r.setCode(code);r.setName(code.name());em.persist(r);}else r=roles.getFirst();
        if(code!=RoleCode.CUSTOMER){for(var p:List.of(SystemPermission.VIEW_POLICIES,SystemPermission.MANAGE_POLICIES,SystemPermission.VIEW_SUPPORT,SystemPermission.MANAGE_SUPPORT)){
            var rows=em.createQuery("select p from Permission p where p.code=:c",Permission.class).setParameter("c",p.code()).getResultList();Permission permission;
            if(rows.isEmpty()){permission=new Permission();permission.setCode(p.code());permission.setName(p.code());em.persist(permission);}else permission=rows.getFirst();r.getPermissions().add(permission);
        }}
        var u=new User();u.setEmail(UUID.randomUUID()+"@isolated.invalid");u.setFullName("ISOLATED TEST");u.setPasswordHash("test-only-not-a-login");u.setStatus(UserStatus.ACTIVE);u.setRoles(new HashSet<>(Set.of(r)));em.persist(u);return u;
    }
    private ActorPrincipal actor(User u,RoleCode role){var grants=new HashSet<String>();u.getRoles().forEach(r->r.getEffectivePermissions().forEach(p->grants.add(p.getCode())));return new ActorPrincipal(u.getId(),UUID.randomUUID(),Set.of(role),grants,role==RoleCode.STAFF?Map.of(facility,FacilityScopeLevel.OPERATE):Map.of());}
    private CommunicationPolicyService.Policy publish(long revision){return policies.publish(businessActor,facility,new CommunicationPolicyService.Input(revision,LocalDate.now().minusDays(1),null,60,7,true));}
    private UUID resolve(){support.resolve(staffActor,ticket,new SupportCommands.Resolution("Đã kiểm tra yêu cầu",List.of(),version),"resolve-"+ticket);return tx.execute(s->{var t=em.find(SupportTicket.class,ticket);var state=em.find(SupportState.class,ticket);var event=em.createQuery("select e from SupportEvent e where e.ticket.id=:id and e.type='RESOLVED'",SupportEvent.class).setParameter("id",ticket).getSingleResult();assertThat(event.getRecordedAt()).isEqualTo(state.getResolvedAt());assertThat(t.getStatus()).isEqualTo(SupportTicketStatus.resolved);return event.getId();});}
    private UUID notice(){return tx.execute(s->{Object id=em.createNativeQuery("select id from notification_outbox where resource_id=:id").setParameter("id",ticket.toString()).getResultStream().findFirst().orElseThrow();return UUID.fromString(id.toString());});}
    private Optional<SupportSources.ReviewNotice> proof(UUID event){return tx.execute(s->{var t=em.find(SupportTicket.class,ticket);var st=em.find(SupportState.class,ticket);var now=clock.instant();return source.reviewNotice(t,event,policies.read(t,st,now).orElseThrow(),now);});}
    private long notifications(){return tx.execute(s->em.createQuery("select count(n) from Notification n where n.user.id=:u and n.relatedEntityId=:t",Long.class).setParameter("u",customer).setParameter("t",ticket).getSingleResult());}

    @Test void actualResolveDeliveryAndAuthenticatedReceiptMatchExactlyTheCurrentCycle(){
        publish(0);UUID event=resolve(),id=notice();assertThat(outbox.read(id,false).orElseThrow().status()).isEqualTo("PENDING");assertThat(notifications()).isZero();assertThat(proof(event)).isEmpty();
        delivery.deliver(id);var sent=outbox.read(id,false).orElseThrow();assertThat(sent.status()).isEqualTo("INBOX");assertThat(notifications()).isEqualTo(1);assertThat(proof(event)).isEmpty();
        var receipt=delivery.acknowledge(customerActor,sent.notificationId());assertThat(receipt.status()).isEqualTo("ACKNOWLEDGED");assertThat(proof(event)).isPresent();
        assertThat(delivery.acknowledge(customerActor,sent.notificationId()).receivedAt()).isEqualTo(receipt.receivedAt());delivery.deliver(id);assertThat(notifications()).isEqualTo(1);
        assertThat(proof(UUID.randomUUID())).isEmpty();
    }
    @Test void acknowledgementProjectionDistinguishesUntrackedInboxAndActualReceiptWithoutWriting() {
        var ordinary=tx.execute(s->{var n=new Notification();n.setUser(em.find(User.class,customer));n.setType(NotificationType.SUPPORT);n.setTitle("Staff replied to Support");n.setContent("TEST");n.setRelatedEntityId(ticket);em.persist(n);em.flush();return n.getId();});
        var untracked=delivery.acknowledgementState(customerActor,ordinary);
        assertThat(untracked.status()).isEqualTo("UNTRACKED");assertThat(untracked.acknowledgementAllowed()).isFalse();assertThat(untracked.receivedAt()).isNull();
        assertThatThrownBy(()->delivery.acknowledge(customerActor,ordinary)).hasMessageContaining("Tracked notification not found");
        assertThatThrownBy(()->delivery.acknowledgementState(otherActor,ordinary)).hasMessageContaining("not found");
        publish(0);UUID event=resolve(),id=notice();delivery.deliver(id);UUID notification=outbox.read(id,false).orElseThrow().notificationId();
        var available=delivery.acknowledgementState(customerActor,notification);
        assertThat(available.status()).isEqualTo("AVAILABLE");assertThat(available.acknowledgementAllowed()).isTrue();assertThat(available.receivedAt()).isNull();
        assertThat(outbox.read(id,false).orElseThrow().status()).isEqualTo("INBOX");assertThat(proof(event)).isEmpty();
        Boolean readFlag=tx.execute(s->em.find(Notification.class,notification).isRead());assertThat(readFlag).isFalse();
        var receipt=delivery.acknowledge(customerActor,notification);var confirmed=delivery.acknowledgementState(customerActor,notification);
        assertThat(confirmed.status()).isEqualTo("ACKNOWLEDGED");assertThat(confirmed.acknowledgementAllowed()).isFalse();assertThat(confirmed.receivedAt()).isEqualTo(receipt.receivedAt());
        SupportTicketStatus ticketStatus=tx.execute(s->em.find(SupportTicket.class,ticket).getStatus());assertThat(ticketStatus).isEqualTo(SupportTicketStatus.resolved);
        tx.executeWithoutResult(s->em.find(User.class,customer).setStatus(UserStatus.INACTIVE));
        assertThatThrownBy(()->delivery.acknowledgementState(customerActor,notification)).hasMessageContaining("role/permissions");
    }
    @Test void wrongCustomerCannotAcknowledgeAndRevokedCustomerCannotSupplyProof(){
        publish(0);UUID event=resolve(),id=notice();delivery.deliver(id);UUID n=outbox.read(id,false).orElseThrow().notificationId();
        assertThatThrownBy(()->delivery.acknowledge(otherActor,n)).hasMessageContaining("not found");
        tx.executeWithoutResult(s->em.find(User.class,customer).setStatus(UserStatus.INACTIVE));
        assertThatThrownBy(()->delivery.acknowledge(customerActor,n)).hasMessageContaining("role/permissions");assertThat(proof(event)).isEmpty();
    }
    @Test void oldPolicyReceiptDoesNotCertifyNewPolicy(){publish(0);UUID event=resolve(),id=notice();delivery.deliver(id);delivery.acknowledge(customerActor,outbox.read(id,false).orElseThrow().notificationId());assertThat(proof(event)).isPresent();publish(1);assertThat(proof(event)).isEmpty();}
    @Test void inactiveRecipientRetriesWithoutCreatingNotificationOrFakeProof(){
        publish(0);UUID event=resolve(),id=notice();tx.executeWithoutResult(s->em.find(User.class,customer).setStatus(UserStatus.INACTIVE));delivery.deliver(id);
        var failed=outbox.read(id,false).orElseThrow();assertThat(failed.status()).isEqualTo("PENDING");assertThat(failed.attempts()).isEqualTo(1);assertThat(notifications()).isZero();assertThat(proof(event)).isEmpty();
        tx.executeWithoutResult(s->em.find(User.class,customer).setStatus(UserStatus.ACTIVE));clock.advance(Duration.ofSeconds(31));delivery.deliver(id);assertThat(outbox.read(id,false).orElseThrow().status()).isEqualTo("INBOX");assertThat(notifications()).isEqualTo(1);
    }
    @Test void missingPolicyPreservesOriginalNotificationAndDoesNotQueueFakeReadyNotice(){resolve();assertThat(notifications()).isEqualTo(1);assertThatThrownBy(this::notice).isInstanceOf(NoSuchElementException.class);}
    @Test void stalePolicyAndManagerClaimsCannotPublish(){publish(0);assertThatThrownBy(()->publish(0)).hasMessageContaining("revision");var impostor=new ActorPrincipal(businessActor.userId(),UUID.randomUUID(),Set.of(RoleCode.MANAGER),businessActor.permissions(),Map.of(facility,FacilityScopeLevel.MANAGE));assertThatThrownBy(()->policies.publish(impostor,facility,new CommunicationPolicyService.Input(1L,LocalDate.now(),null,60,7,true))).hasMessageContaining("role/permissions");}
    @Test void callerRollbackAlsoRollsBackJdbcQueueAndJpaResolution(){publish(0);assertThatThrownBy(()->tx.execute(s->{resolve();throw new IllegalStateException("Roll back caller");})).isInstanceOf(IllegalStateException.class);SupportTicketStatus status=tx.execute(s->em.find(SupportTicket.class,ticket).getStatus());assertThat(status).isEqualTo(SupportTicketStatus.in_progress);assertThatThrownBy(this::notice).isInstanceOf(NoSuchElementException.class);assertThat(notifications()).isZero();}
    @Test void autoCloseRequiresReceiptNotJustPersistedInbox(){publish(0);resolve();UUID id=notice();delivery.deliver(id);clock.advance(Duration.ofDays(8));assertThatThrownBy(()->support.autoClose(ticket)).hasMessageContaining("notification delivery unavailable");delivery.acknowledge(customerActor,outbox.read(id,false).orElseThrow().notificationId());assertThat(support.autoClose(ticket)).isTrue();assertThat(support.autoClose(ticket)).isFalse();SupportTicketStatus status=tx.execute(s->em.find(SupportTicket.class,ticket).getStatus());assertThat(status).isEqualTo(SupportTicketStatus.closed);}
    @Test void ledgerDbRowsReadBackWithoutClaimingCompleteOrExposingReceiptEvidence(){
        UUID rental=rental();var absent=ledgerQueries.read(customerActor,rental,RoleCode.CUSTOMER);assertThat(absent.completeness()).isEqualTo("UNKNOWN");assertThat(absent.obligations()).isNull();
        tx.executeWithoutResult(s->{ledger.open(rental,customer,facility);ledger.charge(rental,null,LedgerStore.Kind.RENT,new BigDecimal("1000000"),clock.instant(),"isolated-test-evidence",staff,"charge",0);});
        var view=ledgerQueries.read(customerActor,rental,RoleCode.CUSTOMER);assertThat(view.completeness()).isEqualTo("PARTIAL");assertThat(view.obligations()).hasSize(1);assertThat(view.obligations().getFirst().outstanding()).isEqualByComparingTo("1000000");assertThat(view.receipts()).isEmpty();
        assertThatThrownBy(()->ledgerQueries.read(otherActor,rental,RoleCode.CUSTOMER)).hasMessageContaining("not found");
        assertThat(tx.execute(s->mapper.valueToTree(ledgerQueries.read(customerActor,rental,RoleCode.CUSTOMER))).toString()).doesNotContain("isolated-test-evidence","evidenceFileId","actorId");
    }
    @Test void storedFutureAndExpiredPoliciesDoNotBecomeEffectiveOperationalPolicies() {
        var today=LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        var future=policies.publish(businessActor,facility,new CommunicationPolicyService.Input(0L,today.plusYears(1),null,60,7,false));
        assertThat(policies.read(facility,Instant.now())).isEmpty();
        assertThatThrownBy(()->policies.get(businessActor,facility)).hasMessageContaining("not effective");
        assertThat(readback.read(businessActor,facility,"communication",null)).isEqualTo(future);
        var expired=policies.publish(businessActor,facility,new CommunicationPolicyService.Input(1L,today.minusYears(2),today.minusYears(1),120,7,true));
        assertThat(policies.read(facility,Instant.now())).isEmpty();
        assertThat(readback.read(businessActor,facility,"communication",null)).isEqualTo(expired);
        assertThat(readback.read(businessActor,facility,"communication",1L)).isEqualTo(future);
        assertThatThrownBy(()->readback.read(businessActor,facility,"communication",3L)).hasMessageContaining("cannot be verified");
    }
    @Test void historicalRenewalPublicationIsBoundToFacilityRevisionAndPublisher() {
        var today=LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        var first=renewalPolicies.publish(businessActor,facility,new com.storagehub.api.renewal.PublishedRenewalPolicy.Input(0L,today.plusYears(1),null,30,24,30,new BigDecimal("0.2"),Set.of(),null,null));
        var second=renewalPolicies.publish(businessActor,facility,new com.storagehub.api.renewal.PublishedRenewalPolicy.Input(1L,today.plusYears(2),null,60,48,30,new BigDecimal("0.3"),Set.of(),null,null));
        assertThat(readback.read(businessActor,facility,"renewal",null)).isEqualTo(second);
        assertThat(readback.read(businessActor,facility,"renewal",1L)).isEqualTo(first);
        assertThatThrownBy(()->readback.read(customerActor,facility,"renewal",1L)).hasMessageContaining("role/permissions");
        assertThatThrownBy(()->readback.read(businessActor,UUID.randomUUID(),"renewal",1L)).hasMessageContaining("not been published");
        assertThatThrownBy(()->readback.read(businessActor,facility,"renewal",0L)).hasMessageContaining("Invalid");
        tx.executeWithoutResult(s->em.find(User.class,businessActor.userId()).setStatus(UserStatus.INACTIVE));
        assertThatThrownBy(()->readback.read(businessActor,facility,"renewal",1L)).hasMessageContaining("role/permissions");
    }
    @Test void readbackRoutesAreDocumentedAndRemainAuthenticated() throws Exception {
        var base="http://localhost:"+environment.getProperty("local.server.port");var client=HttpClient.newHttpClient();
        var schema=client.send(HttpRequest.newBuilder(URI.create(base+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());
        var paths=mapper.readTree(schema.body()).path("paths");
        assertThat(paths.path("/api/business/facilities/{id}/{kind}-policy/stored").path("get").path("security").toString()).contains("bearerAuth");
        assertThat(paths.path("/api/manager/renewals/{id}/staff-assignment").path("get").path("security").toString()).contains("bearerAuth");
        assertThat(paths.path("/api/customer/notifications/{id}/acknowledgement").path("get").path("security").toString()).contains("bearerAuth");
        for(String path:List.of("/api/customer/notifications/"+UUID.randomUUID()+"/acknowledgement","/api/business/facilities/"+facility+"/communication-policy/stored?revision=1","/api/business/facilities/"+facility+"/renewal-policy/stored","/api/manager/renewals/"+UUID.randomUUID()+"/staff-assignment"))
            assertThat(client.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }
    private UUID rental(){return tx.execute(s->{
        var f=em.find(Facility.class,facility);var c=em.find(User.class,customer);var now=clock.instant();var start=LocalDate.now();
        var type=new UnitType();type.setFacility(f);type.setCode(UUID.randomUUID().toString().substring(0,8));type.setName("TEST");type.setLengthM(BigDecimal.ONE);type.setWidthM(BigDecimal.ONE);type.setHeightM(BigDecimal.ONE);type.setMonthlyPrice(BigDecimal.TEN);type.setMaxLoadKg(BigDecimal.ONE);type.setRackLengthM(BigDecimal.ONE);type.setRackWidthM(BigDecimal.ONE);type.setRackHeightM(BigDecimal.ONE);em.persist(type);
        var unit=new StorageUnit();unit.setFacility(f);unit.setUnitType(type);unit.setCode(UUID.randomUUID().toString().substring(0,8));em.persist(unit);
        var q=new ReservationQuote();q.setCustomer(c);q.setFacility(f);q.setUnitType(type);q.setPricingPackageCode("TEST");q.setPolicyVersion("TEST");q.setStartDate(start);q.setEndDate(start.plusMonths(1));q.setRentalMonths(1);q.setMonthlyPrice(BigDecimal.TEN);q.setSubtotal(BigDecimal.TEN);q.setDiscountRate(BigDecimal.ZERO);q.setDiscountAmount(BigDecimal.ZERO);q.setTotalAfterDiscount(BigDecimal.TEN);q.setReservationDepositAmount(BigDecimal.ZERO);q.setSecurityDepositAmount(BigDecimal.ZERO);q.setRemainingRentalAmount(BigDecimal.TEN);q.setDueAtCheckIn(BigDecimal.TEN);q.setTotalInitialObligation(BigDecimal.TEN);q.setQuotedAt(now);q.setExpiresAt(now.plusSeconds(1800));em.persist(q);
        var res=new Reservation();res.setCustomer(c);res.setFacility(f);res.setUnitType(type);res.setSourceQuote(q);res.setReservationCode(UUID.randomUUID().toString().substring(0,24));res.setIdempotencyKey(UUID.randomUUID().toString());res.setAssignedUnit(unit);res.setStartDate(q.getStartDate());res.setEndDate(q.getEndDate());res.setStatus(ReservationStatus.COMPLETED);em.persist(res);
        var rental=new Rental();rental.setCustomer(c);rental.setFacility(f);rental.setStorageUnit(unit);rental.setReservation(res);rental.setStartDate(q.getStartDate());rental.setContractEndDate(q.getEndDate());rental.setMonthlyPrice(BigDecimal.TEN);em.persist(rental);em.flush();return rental.getId();
    });}
    @Test void enabledSwaggerRoutesRequireBearerAndExistingNotificationRouteStillExists() throws Exception {
        var base="http://127.0.0.1:"+environment.getProperty("local.server.port");var client=HttpClient.newHttpClient();
        var result=client.send(HttpRequest.newBuilder(URI.create(base+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(result.statusCode()).isEqualTo(200);var paths=mapper.readTree(result.body()).path("paths");
        for(String role:List.of("customer","manager","business")){var p="/api/"+role+"/rentals/{id}/ledger";assertThat(paths.path(p).path("get").path("security").toString()).contains("bearerAuth");var unauthorized=client.send(HttpRequest.newBuilder(URI.create(base+p.replace("{id}",UUID.randomUUID().toString()))).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(unauthorized.statusCode()).isEqualTo(401);}
        assertThat(paths.path("/api/business/facilities/{id}/communication-policy").has("put")).isTrue();assertThat(paths.path("/api/customer/notifications/{id}/acknowledgement").path("post").path("security").toString()).contains("bearerAuth");assertThat(paths.has("/api/notifications")).isTrue();
    }
    @Test void overdueAdapterBindsActualRentalCustomerAndPublishedCooldown(){
        UUID rental=rental();assertThat(tx.execute(s->source.policy(em.find(Rental.class,rental))).isPresent()).isFalse();publish(0);
        UUID queued=tx.execute(s->{var r=em.find(Rental.class,rental);var policy=source.policy(r).orElseThrow();assertThat(policy.cooldown()).isEqualTo(Duration.ofMinutes(60));return source.enqueue(r,"rental-payment-case","Hồ sơ có khoản quá hạn", "reminder-"+rental,clock.instant());});
        var notice=outbox.read(queued,false).orElseThrow();assertThat(notice.recipient()).isEqualTo(customer);assertThat(notice.resource()).isEqualTo(rental);assertThat(notice.kind()).isEqualTo("OVERDUE");assertThat(notice.status()).isEqualTo("PENDING");assertThat(notice.acknowledgedAt()).isNull();
        UUID replay=tx.execute(s->source.enqueue(em.find(Rental.class,rental),"rental-payment-case","Hồ sơ có khoản quá hạn","reminder-"+rental,clock.instant()));assertThat(replay).isEqualTo(queued);
    }
}
