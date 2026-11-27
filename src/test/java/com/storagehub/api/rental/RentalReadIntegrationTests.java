package com.storagehub.api.rental;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.storagehub.common.api.*;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.security.*;
import com.storagehub.service.RentalQueryService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.LinkedMultiValueMap;

@DataJpaTest
@Import({RentalQueryService.class, com.storagehub.testsupport.DisplayCodeTestConfiguration.class})
@ActiveProfiles("test")
class RentalReadIntegrationTests {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.storagehub.service.InventoryDecisionService inventoryDecisions;
    @Autowired EntityManager em;
    @Autowired RentalQueryService service;
    @Autowired RentalRepository repo;
    @Autowired com.storagehub.domain.repo.ViolationRecoveryRepository recoveryRepository;
    @Autowired com.storagehub.domain.repo.ReturnCaseRepository returnRepository;
    @Autowired com.storagehub.service.BusinessRecordCodes codes;
    @Autowired javax.sql.DataSource dataSource;

    @org.junit.jupiter.api.BeforeEach
    void provisionDisplayCodeFormulaFixtures() {
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("create table if not exists business_record_codes (resource_key varchar(120) primary key, display_code varchar(16))");
        jdbc.execute("create table if not exists money_receipt_codes (source_type varchar(24), source_id binary(16), receipt_code varchar(20))");
        jdbc.execute("create alias if not exists bin_to_uuid for 'com.storagehub.service.ReturnClearanceConcurrencyTests.binaryUuid'");
    }

    @Test void realQueriesScopeFilterPaginateAndHideOtherOwners() {
        Rental own = fixture("A", "Unit_%", "Dương", LocalDate.of(2026,10,31));
        Rental other = fixture("B", "Unit-ab", "Other", LocalDate.of(2026,11,30));
        em.flush(); em.clear();
        var customer = actor(own, false, Map.of());
        var query = RentalQuery.parse(new LinkedMultiValueMap<>(),false);
        assertThat(service.list(customer,query,false,"test").data()).extracting(RentalSummaryResponse::id).containsExactly(own.getId());
        assertThatThrownBy(() -> service.detail(customer,other.getId(),false))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
        var manager = actor(own,true,Map.of(own.getFacility().getId(),FacilityScopeLevel.READ,other.getFacility().getId(),FacilityScopeLevel.MANAGE));
        var params = new LinkedMultiValueMap<String,String>(); params.add("size","1"); params.add("sort","contractEndDate,asc");
        when(codes.findAll(List.of("TH:" + own.getId()))).thenReturn(Map.of("TH:" + own.getId(), "SH-123456"));
        var result = service.list(manager,RentalQuery.parse(params,true),true,"test");
        assertThat(result.pagination().totalItems()).isEqualTo(2); assertThat(result.data()).hasSize(1);
        assertThat(result.data().getFirst().displayCode()).isEqualTo("SH-123456");
        when(codes.find("TH:" + own.getId())).thenReturn("SH-123456");
        assertThat(service.detail(manager, own.getId(), true).displayCode()).isEqualTo("SH-123456");
        params.add("endTo","2026-10-31");
        assertThat(service.list(manager,RentalQuery.parse(params,true),true,"test").pagination().totalItems()).isEqualTo(1);
        params.clear(); params.add("search","_%");
        assertThat(service.list(manager,RentalQuery.parse(params,true),true,"test").data()).extracting(RentalSummaryResponse::id).containsExactly(own.getId());
        params.clear(); params.add("search","dương");
        assertThat(service.list(manager,RentalQuery.parse(params,true),true,"test").data()).hasSize(1);
        params.clear(); params.add("search",other.getId().toString());
        assertThat(service.list(customer,RentalQuery.parse(params,false),false,"test").data()).isEmpty();
        params.clear(); params.add("search", "sh-654321");
        when(codes.resource("TH", "sh-654321")).thenReturn("TH:" + other.getId());
        assertThat(service.list(manager, RentalQuery.parse(params, true), true, "test").data())
            .extracting(RentalSummaryResponse::id).containsExactly(other.getId());
        assertThat(service.list(customer, RentalQuery.parse(params, false), false, "test").data()).isEmpty();
        params.clear(); params.add("facilityId",UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.list(manager,RentalQuery.parse(params,true),true,"test"))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getStatus().value()).isEqualTo(403));
        assertThat(repo.existsActiveRental(own.getReservation().getId(),own.getStorageUnit().getId(),RentalStatus.active)).isTrue();
    }

    @Test void staffRentalReadsAreLimitedToAssignedFacilities() {
        var own = fixture("A", "Unit-A", "Customer A", LocalDate.of(2026, 11, 30));
        var other = fixture("B", "Unit-B", "Customer B", LocalDate.of(2026, 11, 30));
        em.flush();
        em.clear();
        var staff = new ActorPrincipal(UUID.randomUUID(), UUID.randomUUID(), Set.of(RoleCode.STAFF),
            Set.of(SystemPermission.VIEW_RENTALS.code()),
            Map.of(own.getFacility().getId(), FacilityScopeLevel.OPERATE,
                other.getFacility().getId(), FacilityScopeLevel.READ));
        var params = new LinkedMultiValueMap<String, String>();
        assertThat(service.list(staff, RentalQuery.parse(params, true), RoleCode.STAFF, "test").data())
            .extracting(RentalSummaryResponse::id).containsExactly(own.getId());
        params.add("search", own.getReservation().getReservationCode());
        assertThat(service.list(staff, RentalQuery.parse(params, true), RoleCode.STAFF, "test").data())
            .extracting(RentalSummaryResponse::id).containsExactly(own.getId());
        assertThat(service.staffDetail(staff, own.getId()).reservationCode())
            .isEqualTo(own.getReservation().getReservationCode());
        assertThatThrownBy(() -> service.staffDetail(staff, other.getId()))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
        params.add("facilityId", other.getFacility().getId().toString());
        assertThatThrownBy(() -> service.list(staff, RentalQuery.parse(params, true), RoleCode.STAFF, "test"))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        assertThatThrownBy(() -> service.detail(staff, own.getId(), true))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
    }

    @Test void listAndDetailSharePhysicalIntakeProgressWithoutChangingPaidDates() {
        Rental r=fixture("INTAKE","INTAKE-1","Customer",LocalDate.of(2026,10,31));
        r.setSetupStartedAt(Instant.parse("2026-10-01T00:00:00Z"));
        r.setMoveInCompletedAt(Instant.parse("2026-10-02T00:00:00Z"));
        r.getStorageUnit().setStatus(StorageUnitStatus.assigned);em.flush();
        var customer=actor(r,false,Map.of());
        var query=RentalQuery.parse(new LinkedMultiValueMap<>(),false);
        assertThat(service.list(customer,query,false,"test").data().getFirst().intakeCompleted()).isFalse();
        assertThat(service.detail(customer,r.getId(),false).intakeCompleted()).isFalse();
        r.getStorageUnit().setStatus(StorageUnitStatus.occupied);
        r.setInventoryVerifiedAt(Instant.parse("2026-10-03T00:00:00Z"));r.setInventoryPending(false);em.flush();
        assertThat(service.list(customer,query,false,"test").data().getFirst().intakeCompleted()).isTrue();
        assertThat(service.detail(customer,r.getId(),false).intakeCompleted()).isTrue();
        assertThat(r.getStatus()).isEqualTo(RentalStatus.active);
        assertThat(r.getContractEndDate()).isEqualTo(LocalDate.of(2026,10,31));
    }

    @Test void httpEnvelopeValidationUnknownAndReadOnly() throws Exception {
        Rental r=fixture("HTTP","S-1","Customer",LocalDate.of(2026,10,31)); em.flush(); em.clear();
        ActorContext context=mock(ActorContext.class); when(context.required()).thenReturn(actor(r,false,Map.of()));
        var mvc=MockMvcBuilders.standaloneSetup(new CustomerRentalReadController(context,service),new ManagerRentalController(context,service, null))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/customer/rental-records")).andExpect(status().isOk()).andExpect(jsonPath("pagination.totalItems").value(1));
        mvc.perform(get("/api/customer/rental-records/"+r.getId())).andExpect(status().isOk())
            .andExpect(jsonPath("data.monthlyPrice").value(9500000)).andExpect(jsonPath("data.unitType.name").value("Medium"))
            .andExpect(jsonPath("data.financialSummary.completeness").value("UNKNOWN"))
            .andExpect(jsonPath("data.dateSemantics.completeness").value("UNKNOWN"))
            .andExpect(jsonPath("data.dateSemantics.lastPermittedDate").isEmpty())
            .andExpect(jsonPath("data.dateSemantics.endExclusive").isEmpty())
            .andExpect(jsonPath("data.contractEndDate").value("2026-10-31"))
            .andExpect(jsonPath("data.access.completeness").value("UNKNOWN"))
            .andExpect(jsonPath("data.accessPin").doesNotExist());
        mvc.perform(get("/api/customer/rental-records").param("needsAttention","false")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/customer/rental-records").param("page","0","1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/customer/rental-records").param("facilityId",r.getFacility().getId().toString())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/customer/rental-records/"+r.getId()).param("search","Customer")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/customer/rental-records/not-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/customer/rental-records/"+UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/manager/rentals")).andExpect(status().isForbidden());
        when(context.required()).thenReturn(actor(r,true,Map.of(r.getFacility().getId(),FacilityScopeLevel.READ)));
        mvc.perform(get("/api/customer/rental-records")).andExpect(status().isForbidden());
        mvc.perform(get("/api/customer/rental-records/"+r.getId())).andExpect(status().isForbidden());
        when(context.required()).thenThrow(ApiExceptions.unauthorized("No valid session"));
        mvc.perform(get("/api/customer/rental-records")).andExpect(status().isUnauthorized());
        em.flush(); em.clear(); assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.findById(r.getId()).orElseThrow().getMonthlyPrice()).isEqualByComparingTo("9500000");
    }

    @Test void inconsistentScopedRecordFailsWithoutRepair() {
        Rental r=fixture("BROKEN","B-1","Customer",LocalDate.of(2026,10,31));
        r.setMonthlyPrice(new BigDecimal("-1")); em.flush(); em.clear();
        assertThatThrownBy(() -> service.detail(actor(r,false,Map.of()),r.getId(),false))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getStatus().value()).isEqualTo(409));
        assertThat(repo.findById(r.getId()).orElseThrow().getMonthlyPrice()).isEqualByComparingTo("-1");
    }

    @Test void managerWorkQueueOrdersAcrossPagesBeforeLimitingAndKeepsHistoryLast() {
        Rental active=fixture("Q-A","Q-A","Active",LocalDate.of(2026,11,27));
        Rental disputed=fixture("Q-B","Q-B","Disputed",LocalDate.of(2027,11,27));
        Rental history=fixture("Q-C","Q-C","History",LocalDate.of(2026,10,15));
        history.setStatus(RentalStatus.completed); history.setCompletedAt(Instant.now());
        var rc=new ReturnCase(); rc.setRental(disputed); rc.setFacility(disputed.getFacility());
        rc.setStorageUnit(disputed.getStorageUnit()); rc.setCustomer(disputed.getCustomer());
        rc.setScheduledDate(LocalDate.of(2026,11,27)); rc.setStatus(ReturnCaseStatus.disputed);
        rc.setDepositAmount(BigDecimal.ZERO); rc.setRequestedAt(Instant.now()); rc.setRequestedBy(disputed.getCustomer()); em.persist(rc);
        em.flush(); em.clear();
        var scopes=Map.of(active.getFacility().getId(),FacilityScopeLevel.READ,
            disputed.getFacility().getId(),FacilityScopeLevel.READ,history.getFacility().getId(),FacilityScopeLevel.READ);
        var manager=actor(active,true,scopes);
        var p=new LinkedMultiValueMap<String,String>(); p.set("size","1");
        assertThat(service.list(manager,RentalQuery.parse(p,true),true,"queue").data()).extracting(RentalSummaryResponse::id).containsExactly(disputed.getId());
        p.set("page","1");
        assertThat(service.list(manager,RentalQuery.parse(p,true),true,"queue").data()).extracting(RentalSummaryResponse::id).containsExactly(active.getId());
        p.set("page","2");
        assertThat(service.list(manager,RentalQuery.parse(p,true),true,"queue").data()).extracting(RentalSummaryResponse::id).containsExactly(history.getId());
    }

    @Test void customerReservationQueueUsesNextActionBeforePageLimit() {
        var waiting=fixture("CUST-Q1","CUST-Q1","Queue",LocalDate.of(2026,11,27));
        var payment=fixture("CUST-Q2","CUST-Q2","Queue",LocalDate.of(2026,11,27));
        payment.setCustomer(waiting.getCustomer()); payment.getReservation().setCustomer(waiting.getCustomer());
        payment.getReservation().getSourceQuote().setCustomer(waiting.getCustomer());
        waiting.getReservation().setStatus(ReservationStatus.AWAITING_REVIEW);
        payment.getReservation().setStatus(ReservationStatus.AWAITING_PAYMENT);
        payment.getReservation().setPaymentExpiresAt(Instant.now().plusSeconds(3600));
        em.flush(); em.clear();
        var rows=em.createQuery("select reservation from Reservation reservation where reservation.customer.id=:customer"+
            com.storagehub.domain.repo.ReservationRepository.CUSTOMER_QUEUE_ORDER,Reservation.class)
            .setParameter("customer",waiting.getCustomer().getId()).setMaxResults(1).getResultList();
        assertThat(rows).extracting(Reservation::getId).containsExactly(payment.getReservation().getId());
    }

    @Test void staffReturnQueuePutsPresentCustomerAheadAndLeavesExpiredIdentityReviewToManager() {
        var rental=fixture("RETURN-Q","RETURN-Q","Queue",LocalDate.now().plusDays(10));
        var rows=new ArrayList<ReturnCase>(); var now=Instant.now(); var today=LocalDate.now(com.storagehub.time.BusinessTime.ZONE);
        for(int i=0;i<3;i++) {
            var rc=new ReturnCase(); rc.setRental(rental); rc.setCustomer(rental.getCustomer());
            rc.setFacility(rental.getFacility()); rc.setStorageUnit(rental.getStorageUnit()); rc.setRequestedBy(rental.getCustomer());
            rc.setRequestedAt(now.minusSeconds(86400)); rc.setScheduledDate(i==0?today.minusDays(2):today.plusDays(1));
            rc.setStatus(ReturnCaseStatus.scheduled);
            if(i>0)rc.setReturnArrivedAt(now.minusSeconds(i==1?60:3600));
            em.persist(rc); rows.add(rc);
        }
        em.flush(); em.clear();
        var first=returnRepository.searchReturnCases(rental.getFacility().getId(),null,Set.of(rental.getFacility().getId()),null,
            org.springframework.data.domain.PageRequest.of(0,1),today);
        var second=returnRepository.searchReturnCases(rental.getFacility().getId(),null,Set.of(rental.getFacility().getId()),null,
            org.springframework.data.domain.PageRequest.of(1,1),today);
        assertThat(first).extracting(ReturnCase::getId).containsExactly(rows.get(1).getId());
        assertThat(second).extracting(ReturnCase::getId).containsExactly(rows.get(0).getId());
        assertThat(first.getTotalElements()).isEqualTo(3);
    }

    @Test void recoveryQueuePrioritizesBlockedAndOverdueWorkBeforePagination() {
        var rental=fixture("REC-Q","REC-Q","Queue",LocalDate.of(2026,11,27));
        var cases=new ArrayList<ViolationRecovery>();
        var at=Instant.now();
        for(int i=0;i<3;i++) {
            var v=new ViolationRecovery(); v.setRental(rental); v.setReason(ViolationRecovery.Reason.REMOVAL_OVERDUE);
            v.setSourceDeadlineAt(at.minusSeconds(86400)); v.setRequiredAt(at.minusSeconds(3600));
            v.setStage(i==2?ViolationRecovery.Stage.BLOCKED:ViolationRecovery.Stage.AWAITING_APPROVAL);
            v.setActionDueAt(i==0?at.plusSeconds(3600):i==1?at.minusSeconds(60):at.plusSeconds(7200));
            em.persist(v); cases.add(v);
        }
        cases.getFirst().setEscalated(true);
        em.flush(); em.clear();
        var facilities=Set.of(rental.getFacility().getId());
        var first=recoveryRepository.operationsQueue(false,facilities,true,false,org.springframework.data.domain.PageRequest.of(0,1));
        var second=recoveryRepository.operationsQueue(false,facilities,true,false,org.springframework.data.domain.PageRequest.of(1,1));
        assertThat(first).extracting(ViolationRecovery::getId).containsExactly(cases.get(2).getId());
        assertThat(second).extracting(ViolationRecovery::getId).containsExactly(cases.get(1).getId());
        assertThat(recoveryRepository.operationsQueue(false,facilities,true,true,org.springframework.data.domain.PageRequest.of(0,1)))
            .extracting(ViolationRecovery::getId).containsExactly(cases.getFirst().getId());
    }

    private ActorPrincipal actor(Rental r,boolean manager,Map<UUID,FacilityScopeLevel> scopes) {
        return new ActorPrincipal(r.getCustomer().getId(),UUID.randomUUID(),Set.of(manager?RoleCode.MANAGER:RoleCode.CUSTOMER),
            manager?Set.of(SystemPermission.VIEW_RENTALS.code()):Set.of(),scopes);
    }
    // Explicit test-only H2 fixtures; never inserted into runtime/shared DB.
    private Rental fixture(String code,String unitCode,String customerName,LocalDate end) {
        Facility f=new Facility(); f.setCode(code); f.setName(code); f.setAddress("Test"); f.setCity("Test"); em.persist(f);
        User u=new User(); u.setEmail(code+"@test.invalid"); u.setFullName(customerName); u.setPasswordHash("test-only-not-a-login"); em.persist(u);
        UnitType t=new UnitType(); t.setFacility(f); t.setCode("M"); t.setName("Medium");
        t.setLengthM(BigDecimal.ONE); t.setWidthM(BigDecimal.ONE); t.setHeightM(BigDecimal.ONE);
        t.setMonthlyPrice(new BigDecimal("15000000")); t.setMaxLoadKg(BigDecimal.ONE);
        t.setRackLengthM(BigDecimal.ONE); t.setRackWidthM(BigDecimal.ONE); t.setRackHeightM(BigDecimal.ONE); em.persist(t);
        StorageUnit unit=new StorageUnit(); unit.setFacility(f); unit.setUnitType(t); unit.setCode(unitCode); em.persist(unit);
        ReservationQuote quote=com.storagehub.testing.LegacyReservationQuoteFixture.create(); quote.setCustomer(u); quote.setFacility(f); quote.setUnitType(t);
        quote.setPricingPackageCode("TEST"); quote.setPolicyVersion("test-fixture"); quote.setStartDate(LocalDate.of(2026,10,1));
        quote.setEndDate(end.plusDays(1)); quote.setRentalMonths(1); quote.setMonthlyPrice(new BigDecimal("9500000"));
        quote.setSubtotal(new BigDecimal("9500000")); quote.setDiscountRate(BigDecimal.ZERO); quote.setDiscountAmount(BigDecimal.ZERO);
        quote.setTotalAfterDiscount(new BigDecimal("9500000")); quote.setReservationDepositAmount(BigDecimal.ZERO);
        quote.setSecurityDepositAmount(BigDecimal.ZERO); quote.setRemainingRentalAmount(new BigDecimal("9500000"));
        quote.setDueAtCheckIn(new BigDecimal("9500000")); quote.setTotalInitialObligation(new BigDecimal("9500000"));
        quote.setQuotedAt(Instant.parse("2026-09-01T00:00:00Z")); quote.setExpiresAt(Instant.parse("2026-09-02T00:00:00Z")); em.persist(quote);
        Reservation reservation=new Reservation(); reservation.setReservationCode("TEST-"+code); reservation.setCustomer(u);
        reservation.setSourceQuote(quote); reservation.setIdempotencyKey(code); reservation.setFacility(f); reservation.setUnitType(t);
        reservation.setStartDate(quote.getStartDate()); reservation.setEndDate(quote.getEndDate()); em.persist(reservation);
        Rental r=new Rental(); r.setCustomer(u); r.setFacility(f); r.setStorageUnit(unit); r.setReservation(reservation);
        r.setStartDate(quote.getStartDate()); r.setContractEndDate(end); r.setMonthlyPrice(new BigDecimal("9500000")); em.persist(r); return r;
    }
}
