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
@Import(RentalQueryService.class)
@ActiveProfiles("test")
class RentalReadIntegrationTests {
    @Autowired EntityManager em;
    @Autowired RentalQueryService service;
    @Autowired RentalRepository repo;

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
        var result = service.list(manager,RentalQuery.parse(params,true),true,"test");
        assertThat(result.pagination().totalItems()).isEqualTo(2); assertThat(result.data()).hasSize(1);
        params.add("endTo","2026-10-31");
        assertThat(service.list(manager,RentalQuery.parse(params,true),true,"test").pagination().totalItems()).isEqualTo(1);
        params.clear(); params.add("search","_%");
        assertThat(service.list(manager,RentalQuery.parse(params,true),true,"test").data()).extracting(RentalSummaryResponse::id).containsExactly(own.getId());
        params.clear(); params.add("search","dương");
        assertThat(service.list(manager,RentalQuery.parse(params,true),true,"test").data()).hasSize(1);
        params.clear(); params.add("search",other.getId().toString());
        assertThat(service.list(customer,RentalQuery.parse(params,false),false,"test").data()).isEmpty();
        params.clear(); params.add("facilityId",UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.list(manager,RentalQuery.parse(params,true),true,"test"))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getStatus().value()).isEqualTo(403));
        assertThat(repo.existsActiveRental(own.getReservation().getId(),own.getStorageUnit().getId(),RentalStatus.active)).isTrue();
    }

    @Test void httpEnvelopeValidationUnknownAndReadOnly() throws Exception {
        Rental r=fixture("HTTP","S-1","Customer",LocalDate.of(2026,10,31)); em.flush(); em.clear();
        ActorContext context=mock(ActorContext.class); when(context.required()).thenReturn(actor(r,false,Map.of()));
        var mvc=MockMvcBuilders.standaloneSetup(new CustomerRentalController(context,service),new ManagerRentalController(context,service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/customer/rentals")).andExpect(status().isOk()).andExpect(jsonPath("pagination.totalItems").value(1));
        mvc.perform(get("/api/customer/rentals/"+r.getId())).andExpect(status().isOk())
            .andExpect(jsonPath("data.monthlyPrice").value(9500000)).andExpect(jsonPath("data.unitType.name").value("Medium"))
            .andExpect(jsonPath("data.financialSummary.completeness").value("UNKNOWN"))
            .andExpect(jsonPath("data.access.completeness").value("UNKNOWN"))
            .andExpect(jsonPath("data.accessPin").doesNotExist());
        mvc.perform(get("/api/customer/rentals").param("needsAttention","false")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/customer/rentals").param("page","0","1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/customer/rentals/not-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/customer/rentals/"+UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/manager/rentals")).andExpect(status().isForbidden());
        when(context.required()).thenThrow(ApiExceptions.unauthorized("No valid session"));
        mvc.perform(get("/api/customer/rentals")).andExpect(status().isUnauthorized());
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
        ReservationQuote quote=new ReservationQuote(); quote.setCustomer(u); quote.setFacility(f); quote.setUnitType(t);
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
