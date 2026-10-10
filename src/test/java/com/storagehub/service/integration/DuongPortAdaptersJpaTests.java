package com.storagehub.service.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.*;
import com.storagehub.api.support.SupportCommands.Visibility;
import com.storagehub.config.FileProperties;
import com.storagehub.domain.model.*;
import com.storagehub.security.*;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.rental.period.*;
import com.storagehub.service.renewal.integration.*;
import com.storagehub.service.renewal.persistence.*;
import com.storagehub.service.renewal.operations.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;

/** Real isolated H2 persistence and real temporary file bytes; no runtime seed/test adapter. */
@DataJpaTest @ActiveProfiles("test")
@TestPropertySource(properties="storagehub.integration.rental-period.enabled=true")
@Import({DuongResourceAccess.class,DuongFileEvidence.class,RenewalAssignmentSource.class,
    RenewalPolicyPublicationService.class,PublishedRenewalPolicyAdapters.class,
    ProjectHandoffRentalPeriodAdapter.class,RentalPeriodResolver.class,RenewalOperationService.class,
    RenewalPersistence.class,com.storagehub.service.FileStorageService.class,
    com.storagehub.service.AdminAuthorizationService.class,com.storagehub.service.FacilityScopeService.class,
    com.storagehub.service.NotificationService.class,com.storagehub.service.support.SupportService.class,
    com.storagehub.service.support.SupportPersistence.class,DuongPortAdaptersJpaTests.Config.class})
class DuongPortAdaptersJpaTests {
    static final Instant NOW=Instant.parse("2026-10-08T02:00:00Z");
    @TestConfiguration static class Config {
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean AuditLogService audit(){return mock(AuditLogService.class);}
        @Bean FileProperties files(){return new FileProperties();}
        @Bean Clock clock(){return Clock.fixed(NOW,ZoneOffset.UTC);}
        @Bean ActorContext actors(){return mock(ActorContext.class);}
    }
    @Autowired EntityManager em; @Autowired ObjectMapper mapper;
    @Autowired RenewalPolicyPublicationService policies; @Autowired PublishedRenewalPolicyAdapters adapters;
    @Autowired RenewalAssignmentSource assignments; @Autowired DuongFileEvidence files;
    @Autowired FileProperties fileProperties; @Autowired ProjectHandoffRentalPeriodAdapter periods;
    @Autowired RenewalOperationService operations;
    @Autowired com.storagehub.service.FileStorageService storage;
    @Autowired com.storagehub.service.support.SupportService support;
    @TempDir Path root;
    Facility facility;User customer,staff,manager,business;Rental rental;Renewal renewal;
    @BeforeEach void fixture() throws Exception {
        fileProperties.setStoragePath(root.toString());
        facility=new Facility();facility.setCode(UUID.randomUUID().toString().substring(0,8));facility.setName("TEST");facility.setAddress("TEST");facility.setCity("TEST");em.persist(facility);
        var permissions=new HashSet<Permission>();
        for(var code:List.of(SystemPermission.VIEW_RENTALS,SystemPermission.MANAGE_RENTALS,SystemPermission.VIEW_PAYMENTS,SystemPermission.MANAGE_PAYMENTS,SystemPermission.VIEW_SUPPORT,SystemPermission.MANAGE_SUPPORT,SystemPermission.VIEW_POLICIES,SystemPermission.MANAGE_POLICIES)){var p=new Permission();p.setCode(code.code());p.setName(code.code());em.persist(p);permissions.add(p);}
        customer=user(RoleCode.CUSTOMER,Set.of());staff=user(RoleCode.STAFF,permissions);manager=user(RoleCode.MANAGER,permissions);business=user(RoleCode.BUSINESS,permissions);
        scope(staff,FacilityScopeLevel.OPERATE);scope(manager,FacilityScopeLevel.MANAGE);
        var type=new UnitType();type.setFacility(facility);type.setCode("TEST");type.setName("TEST");type.setLengthM(BigDecimal.ONE);type.setWidthM(BigDecimal.ONE);type.setHeightM(BigDecimal.ONE);type.setMonthlyPrice(BigDecimal.TEN);type.setMaxLoadKg(BigDecimal.ONE);type.setRackLengthM(BigDecimal.ONE);type.setRackWidthM(BigDecimal.ONE);type.setRackHeightM(BigDecimal.ONE);em.persist(type);
        var unit=new StorageUnit();unit.setFacility(facility);unit.setUnitType(type);unit.setCode("TEST");em.persist(unit);
        var q=new ReservationQuote();q.setCustomer(customer);q.setFacility(facility);q.setUnitType(type);q.setPricingPackageCode("TEST");q.setPolicyVersion("TEST");q.setStartDate(LocalDate.of(2026,10,1));q.setEndDate(LocalDate.of(2026,10,7));q.setRentalMonths(1);q.setMonthlyPrice(BigDecimal.TEN);q.setSubtotal(BigDecimal.TEN);q.setDiscountRate(BigDecimal.ZERO);q.setDiscountAmount(BigDecimal.ZERO);q.setTotalAfterDiscount(BigDecimal.TEN);q.setReservationDepositAmount(BigDecimal.ZERO);q.setSecurityDepositAmount(BigDecimal.ZERO);q.setRemainingRentalAmount(BigDecimal.TEN);q.setDueAtCheckIn(BigDecimal.TEN);q.setTotalInitialObligation(BigDecimal.TEN);q.setQuotedAt(NOW);q.setExpiresAt(NOW.plusSeconds(1800));em.persist(q);
        var res=new Reservation();res.setCustomer(customer);res.setFacility(facility);res.setUnitType(type);res.setSourceQuote(q);res.setReservationCode("TEST");res.setIdempotencyKey("TEST");res.setAssignedUnit(unit);res.setStartDate(q.getStartDate());res.setEndDate(q.getEndDate());res.setStatus(ReservationStatus.COMPLETED);em.persist(res);
        rental=new Rental();rental.setCustomer(customer);rental.setFacility(facility);rental.setStorageUnit(unit);rental.setReservation(res);rental.setStartDate(q.getStartDate());rental.setContractEndDate(q.getEndDate());rental.setMonthlyPrice(BigDecimal.TEN);em.persist(rental);
        var checkin=new CheckIn();checkin.setReservation(res);checkin.setRental(rental);checkin.setPerformedBy(staff);checkin.setStatus(CheckInStatus.completed);em.persist(checkin);em.flush();
        var receipt=new ActivityLog();receipt.setActor(customer);receipt.setFacility(facility);receipt.setAction("CUSTOMER_RECEIPT_CONFIRMED");receipt.setEntityType("Reservation");receipt.setEntityId(res.getId());receipt.setCorrelationId("TEST");receipt.setAfterStateJson(mapper.writeValueAsString(Map.of("status","COMPLETED","rentalId",rental.getId(),"rentalPeriodEvidence",RentalPeriodEvidence.receipt(rental,true))));em.persist(receipt);
        renewal=new Renewal();renewal.setRental(rental);renewal.setRequestedBy(customer);renewal.setStatus(RenewalStatus.approved);renewal.setAmount(BigDecimal.TEN);renewal.setNewEndDate(LocalDate.of(2026,11,6));em.persist(renewal);
        var quote=new RenewalQuote(rental,customer,"{}","test-hash",NOW,NOW.plusSeconds(1800));em.persist(quote);
        var revision=new RenewalAcceptedRevision(renewal,quote,1,NOW,"TEST");em.persist(revision);
        em.persist(new RenewalWorkflow(renewal,revision));em.flush();
    }
    @Test void actualSpringDateSourceReadsPersistedHandoffAndExclusiveEnd(){assertThat(periods.read(rental).orElseThrow().inclusiveEndDate()).isEqualTo(LocalDate.of(2026,10,6));}
    @Test void missingPolicyRemainsUnknownAndDoesNotSeed(){assertThat(adapters.read(rental,LocalDate.of(2026,10,7))).isEmpty();assertThat(adapters.read(renewal)).isEmpty();assertThat(adapters.term(rental,NOW)).isEmpty();assertThat(em.createQuery("select count(s) from SystemSetting s",Long.class).getSingleResult()).isZero();}
    @Test void publishedPolicySurvivesFlushClearAndMapsThreePorts(){
        var p=policies.publish(actor(business,RoleCode.BUSINESS),facility.getId(),input(0));UUID rid=rental.getId();em.flush();em.clear();rental=em.find(Rental.class,rid);renewal=em.find(Renewal.class,renewal.getId());
        assertThat(policies.get(actor(manager,RoleCode.MANAGER),facility.getId()).version()).isEqualTo(p.version());
        assertThat(adapters.read(rental,LocalDate.of(2026,10,7)).orElseThrow().depositRate()).isEqualByComparingTo("0.2");
        assertThat(adapters.read(renewal).orElseThrow().signingWindow()).isEqualTo(Duration.ofMinutes(4320));
        var term=adapters.term(rental,NOW).orElseThrow();assertThat(term.lastPermittedDate()).isEqualTo(LocalDate.of(2026,10,6));assertThat(term.recoveryStart()).isEqualTo(Instant.parse("2026-10-13T17:00:00Z"));
        assertThat(rental.getContractEndDate()).isEqualTo(LocalDate.of(2026,10,7));
    }
    @Test void managerCannotPublishEvenWithManagePolicyClaim(){assertThatThrownBy(()->policies.publish(actor(manager,RoleCode.MANAGER),facility.getId(),input(0))).hasMessageContaining("permission");}
    @Test void policyStaleRevisionRejectedAndVersionChanges(){var p=policies.publish(actor(business,RoleCode.BUSINESS),facility.getId(),input(0));assertThatThrownBy(()->policies.publish(actor(business,RoleCode.BUSINESS),facility.getId(),input(0))).hasMessageContaining("revision");assertThat(policies.publish(actor(business,RoleCode.BUSINESS),facility.getId(),input(1)).version()).isNotEqualTo(p.version());}
    @Test void wrongFacilityPackageAndMissingPrimitiveRejected() throws Exception {
        var p=input(0);assertThatThrownBy(()->policies.publish(actor(business,RoleCode.BUSINESS),facility.getId(),new PublishedRenewalPolicy.Input(0L,p.effectiveFrom(),null,30,24,30,p.depositRate(),Set.of(UUID.randomUUID()),p.signing(),p.term()))).hasMessageContaining("package");
        policies.publish(actor(business,RoleCode.BUSINESS),facility.getId(),p);var setting=em.createQuery("select s from SystemSetting s",SystemSetting.class).getSingleResult();var tree=mapper.readTree(setting.getValue());((com.fasterxml.jackson.databind.node.ObjectNode)tree).remove("requestWindowDays");setting.setValue(mapper.writeValueAsString(tree));em.flush();
        assertThatThrownBy(()->adapters.read(rental,LocalDate.of(2026,10,7))).hasMessageContaining("incomplete");
    }
    @Test void policyOutsideEffectiveDatesAndOptionalSourcesAreEmpty(){var p=input(0);policies.publish(actor(business,RoleCode.BUSINESS),facility.getId(),new PublishedRenewalPolicy.Input(0L,p.effectiveFrom(),p.effectiveFrom(),30,24,30,p.depositRate(),Set.of(),null,null));assertThat(adapters.read(rental,LocalDate.of(2027,1,1))).isEmpty();assertThat(adapters.read(renewal)).isEmpty();assertThat(adapters.term(rental,NOW)).isEmpty();}
    @Test void assignmentIsNotInferredFromRoleOrBooking(){assertThat(assignments.current(renewal)).isEmpty();assertThat(assignments.assignedRenewalIds(actor(staff,RoleCode.STAFF))).isEmpty();assertThatThrownBy(()->assignments.require(actor(staff,RoleCode.STAFF),renewal,RenewalOperationSources.Capability.READ)).hasMessageContaining("not been assigned");}
    @Test void persistedAssignmentAllowsOnlyCurrentStaff() throws Exception {assign(staff,NOW);assertThat(assignments.assignedRenewalIds(actor(staff,RoleCode.STAFF))).containsExactly(renewal.getId());assignments.require(actor(staff,RoleCode.STAFF),renewal,RenewalOperationSources.Capability.COMPLETE);var other=new ActorPrincipal(manager.getId(),UUID.randomUUID(),Set.of(RoleCode.STAFF),actor(staff,RoleCode.STAFF).permissions(),Map.of(facility.getId(),FacilityScopeLevel.OPERATE));assertThatThrownBy(()->assignments.require(other,renewal,RenewalOperationSources.Capability.READ)).isInstanceOf(RuntimeException.class);}
    @Test void ambiguousAssignmentFailsClosed() throws Exception {assign(staff,NOW);assign(staff,NOW);assertThatThrownBy(()->assignments.current(renewal)).hasMessageContaining("ambiguous");}
    @Test void revokedDbScopeOverridesOldClaims() throws Exception {assign(staff,NOW);em.createQuery("delete from UserFacilityScope s where s.user.id=:u").setParameter("u",staff.getId()).executeUpdate();assertThatThrownBy(()->assignments.require(actor(staff,RoleCode.STAFF),renewal,RenewalOperationSources.Capability.COMPLETE)).hasMessageContaining("not found");}
    @Test void managerAssignmentCommandPersistsAndReplaysExactlyOnce(){var body=new RenewalOperationCommands.StaffAssignment(staff.getId(),"TEST assignment",0L);var a=actor(manager,RoleCode.MANAGER);var first=operations.assignStaff(a,renewal.getId(),body,"assign-test");assertThat(operations.assignStaff(a,renewal.getId(),body,"assign-test")).isEqualTo(first);assertThat(assignments.current(renewal).orElseThrow().staffId()).isEqualTo(staff.getId());assertThat(em.createQuery("select count(e) from RenewalOperationEvent e where e.kind='STAFF_ASSIGNMENT'",Long.class).getSingleResult()).isEqualTo(1);assertThat(first.state().missingSources()).doesNotContain("STAFF_PERMISSION_ASSIGNMENT");assertThat(first.state().missingSources()).contains("BO_SIGNING_POLICY","RENEWAL_ACCOUNTING");}
    @Test void facilityFaultNeedsExplicitBoundManagerReview() throws Exception {var incident=new RenewalOperationEvent(renewal,"INCIDENT",NOW,staff.getId(),"{}");em.persist(incident);em.flush();assertThatThrownBy(()->adapters.requireFacilityFault(renewal,incident.getId(),manager.getId())).hasMessageContaining("missing");em.persist(new RenewalOperationEvent(renewal,"FAULT_REVIEW",NOW.plusSeconds(1),manager.getId(),mapper.writeValueAsString(Map.of("incidentId",incident.getId(),"facilityFault",true))));em.flush();adapters.requireFacilityFault(renewal,incident.getId(),manager.getId());assertThatThrownBy(()->adapters.requireFacilityFault(renewal,incident.getId(),staff.getId())).hasMessageContaining("reviewer");}
    @Test void faultReviewCommandPersistsVerdictNotJustNote(){var incident=new RenewalOperationEvent(renewal,"INCIDENT",NOW.minusSeconds(60),staff.getId(),"{}");em.persist(incident);em.flush();operations.reviewFault(actor(manager,RoleCode.MANAGER),renewal.getId(),new RenewalOperationCommands.FaultReview(incident.getId(),true,"TEST verified",List.of(),0L),"fault-test");adapters.requireFacilityFault(renewal,incident.getId(),manager.getId());}
    @Test void revokedFaultReviewerScopeCannotAuthorizeCustomerConfirmation() throws Exception {
        var incident=new RenewalOperationEvent(renewal,"INCIDENT",NOW,staff.getId(),"{}");em.persist(incident);em.flush();
        em.persist(new RenewalOperationEvent(renewal,"FAULT_REVIEW",NOW.plusSeconds(1),manager.getId(),mapper.writeValueAsString(Map.of("incidentId",incident.getId(),"facilityFault",true))));em.flush();
        em.createQuery("delete from UserFacilityScope s where s.user.id=:u").setParameter("u",manager.getId()).executeUpdate();
        assertThatThrownBy(()->adapters.requireFacilityFault(renewal,incident.getId(),manager.getId())).hasMessageContaining("no longer authorized");
    }
    @Test void simultaneousFaultVerdictsAreUnknownNotChosenByUuid() throws Exception {
        var incident=new RenewalOperationEvent(renewal,"INCIDENT",NOW,staff.getId(),"{}");em.persist(incident);em.flush();
        for(boolean verdict:List.of(true,false))em.persist(new RenewalOperationEvent(renewal,"FAULT_REVIEW",NOW.plusSeconds(1),manager.getId(),mapper.writeValueAsString(Map.of("incidentId",incident.getId(),"facilityFault",verdict))));em.flush();
        assertThatThrownBy(()->adapters.requireFacilityFault(renewal,incident.getId(),manager.getId())).hasMessageContaining("ambiguous");
    }
    @Test void publicAttachmentBindsAndCanBeReadFromDb() throws Exception {var ticket=ticket();var f=file(customer,DuongFileEvidence.UPLOAD,customer.getId());files.requireAttach(actor(customer,RoleCode.CUSTOMER),ticket,Visibility.PUBLIC,List.of(f.getId()));em.flush();UUID id=f.getId();em.clear();f=em.find(FileAsset.class,id);ticket=em.find(SupportTicket.class,ticket.getId());assertThat(f.getEntityType()).isEqualTo(DuongFileEvidence.PUBLIC);assertThat(f.getEntityId()).isEqualTo(ticket.getId());files.requireRead(actor(customer,RoleCode.CUSTOMER),ticket,Visibility.PUBLIC,List.of(id));files.requireDownload(actor(customer,RoleCode.CUSTOMER),f);}
    @Test void attachmentCannotRebindOtherTicket() throws Exception {var a=ticket();var b=ticket();var f=file(customer,DuongFileEvidence.PUBLIC,a.getId());assertThatThrownBy(()->files.requireAttach(actor(customer,RoleCode.CUSTOMER),b,Visibility.PUBLIC,List.of(f.getId()))).hasMessageContaining("not found");assertThat(f.getEntityId()).isEqualTo(a.getId());}
    @Test void internalAttachmentCannotBeReadByCustomerEvenUploader() throws Exception {var t=ticket();var f=file(customer,DuongFileEvidence.INTERNAL,t.getId());assertThatThrownBy(()->files.requireDownload(actor(customer,RoleCode.CUSTOMER),f)).hasMessageContaining("not found");files.requireRead(actor(manager,RoleCode.MANAGER),t,Visibility.INTERNAL,List.of(f.getId()));}
    @Test void changedBytesAndMissingFileDoNotCertifyEvidence() throws Exception {var t=ticket();var f=file(customer,DuongFileEvidence.PUBLIC,t.getId());Files.writeString(Path.of(f.getStorageKey()),"TEST BAD!");assertThatThrownBy(()->files.requireRead(actor(customer,RoleCode.CUSTOMER),t,Visibility.PUBLIC,List.of(f.getId()))).hasMessageContaining("changed");Files.delete(Path.of(f.getStorageKey()));assertThatThrownBy(()->files.requireRead(actor(customer,RoleCode.CUSTOMER),t,Visibility.PUBLIC,List.of(f.getId()))).hasMessageContaining("not found");}
    @Test void legacyFileNamespaceIsNotFalselyCertified() throws Exception {var f=file(customer,"CHECK_IN",UUID.randomUUID());assertThat(files.supportsFiles(List.of(f.getId()))).isFalse();}
    @Test void renewalEvidenceEnforcesPurposeAndAssignment() throws Exception {assign(staff,NOW);var f=file(staff,DuongFileEvidence.renewalType("INCIDENT"),renewal.getId());files.require(actor(staff,RoleCode.STAFF),renewal,"INCIDENT",List.of(f.getId()));assertThatThrownBy(()->files.require(actor(staff,RoleCode.STAFF),renewal,"ARRIVAL",List.of(f.getId()))).hasMessageContaining("not found");assertThatThrownBy(()->files.requireUpload(actor(customer,RoleCode.CUSTOMER),DuongFileEvidence.renewalType("SIGNED_CONTRACT"),renewal.getId())).isInstanceOf(RuntimeException.class);}
    @Test void nestedUnknownPolicyFieldsAreRejected(){assertThatThrownBy(()->mapper.convertValue(Map.of("signingWindowMinutes",5,"exceptionExtensionLimitMinutes",1,"amount",1),PublishedRenewalPolicy.Signing.class)).hasMessageContaining("Unknown signing");}
    @Test void actualUploadAndSupportCreateBindPrivateStagingWithoutComplaintCollision() {
        byte[] png={(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a};
        var a=actor(customer,RoleCode.CUSTOMER);var uploaded=storage.store(a,new org.springframework.mock.web.MockMultipartFile("file","evidence.png","image/png",png),DuongFileEvidence.PUBLIC,null);
        assertThat(uploaded.entityType()).isEqualTo(DuongFileEvidence.UPLOAD);assertThat(uploaded.entityId()).isEqualTo(customer.getId());
        var body=new com.storagehub.api.support.SupportCommands.Create("TEST upload","TEST help",facility.getId(),null,List.of(uploaded.id()));
        var created=support.create(a,body,"create-real-file");assertThat(support.create(a,body,"create-real-file").id()).isEqualTo(created.id());
        em.flush();em.clear();var asset=em.find(FileAsset.class,uploaded.id());assertThat(asset.getEntityType()).isEqualTo(DuongFileEvidence.PUBLIC);assertThat(asset.getEntityId()).isEqualTo(created.id());
        var q=new com.storagehub.api.support.SupportQuery(0,20,null,null,null,"","createdAt",false);
        var messages=support.messages(a,com.storagehub.service.support.SupportService.Audience.CUSTOMER,created.id(),q,"TEST").data();
        assertThat(messages.getFirst().evidenceCompleteness()).isEqualTo("COMPLETE");assertThat(messages.getFirst().evidenceFileIds()).containsExactly(uploaded.id());
        assertThat(storage.download(a,asset.getId()).sizeBytes()).isEqualTo(png.length);
    }
    @Test void legacyAttachmentHistoryStillReadsUnknownInsteadOfFailingWholeTimeline() throws Exception {
        var t=ticket();var f=file(customer,"CHECK_IN",UUID.randomUUID());
        em.persist(new com.storagehub.service.support.SupportMessage(t,customer.getId(),"CUSTOMER","PUBLIC","TEST old file",mapper.writeValueAsString(List.of(f.getId())),NOW));em.flush();
        var q=new com.storagehub.api.support.SupportQuery(0,20,null,null,null,"","createdAt",false);
        var m=support.messages(actor(customer,RoleCode.CUSTOMER),com.storagehub.service.support.SupportService.Audience.CUSTOMER,t.getId(),q,"TEST").data().getFirst();
        assertThat(m.evidenceCompleteness()).isEqualTo("UNKNOWN");assertThat(m.evidenceFileIds()).isNull();assertThat(m.body()).isEqualTo("TEST old file");
    }
    private PublishedRenewalPolicy.Input input(long revision){return new PublishedRenewalPolicy.Input(revision,LocalDate.of(2026,10,1),null,30,24,30,new BigDecimal("0.2"),Set.of(),new PublishedRenewalPolicy.Signing(4320,1440),new PublishedRenewalPolicy.Term("CALENDAR_DAYS","Asia/Ho_Chi_Minh",3,6,7,8,LocalTime.of(17,0),LocalTime.MIDNIGHT));}
    private User user(RoleCode code,Set<Permission> permissions){var r=new Role();r.setCode(code);r.setName(code.name());r.setPermissions(new HashSet<>(permissions));em.persist(r);var u=new User();u.setEmail(code.name()+"@test.invalid");u.setFullName("TEST "+code);u.setPasswordHash("test-only");u.setStatus(UserStatus.ACTIVE);u.setRoles(new HashSet<>(Set.of(r)));em.persist(u);return u;}
    private void scope(User user,FacilityScopeLevel level){var s=new UserFacilityScope();s.setUser(user);s.setFacility(facility);s.setScopeLevel(level);em.persist(s);}
    private ActorPrincipal actor(User u,RoleCode role){var grants=new HashSet<String>();u.getRoles().forEach(r->r.getEffectivePermissions().forEach(p->grants.add(p.getCode())));return new ActorPrincipal(u.getId(),UUID.randomUUID(),Set.of(role),grants,role==RoleCode.CUSTOMER||role==RoleCode.BUSINESS?Map.of():Map.of(facility.getId(),role==RoleCode.STAFF?FacilityScopeLevel.OPERATE:FacilityScopeLevel.MANAGE));}
    private void assign(User u,Instant at) throws Exception {var w=em.find(RenewalWorkflow.class,renewal.getId());em.persist(new RenewalOperationEvent(renewal,"STAFF_ASSIGNMENT",at,manager.getId(),mapper.writeValueAsString(new RenewalAssignmentSource.Assignment(renewal.getId(),facility.getId(),u.getId(),w.getAcceptedRevision().getQuote().getId(),w.getVersion()))));em.flush();}
    private SupportTicket ticket(){var t=new SupportTicket();t.setCustomer(customer);t.setFacility(facility);t.setAssignedTo(staff);t.setSubject("TEST");t.setDescription("TEST");em.persist(t);em.flush();return t;}
    private FileAsset file(User owner,String type,UUID resource) throws Exception {var path=Files.createTempFile(root,"duong-", ".txt");byte[] bytes="TEST FILE".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(path,bytes);var f=new FileAsset();f.setUploadedBy(owner);f.setStorageKey(path.toString());f.setOriginalName("test.txt");f.setContentType("text/plain");f.setSizeBytes(bytes.length);f.setChecksumSha256(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));f.setEntityType(type);f.setEntityId(resource);em.persist(f);em.flush();return f;}
}
