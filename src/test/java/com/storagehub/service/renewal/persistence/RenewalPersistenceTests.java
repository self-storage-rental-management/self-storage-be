package com.storagehub.service.renewal.persistence;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest @ActiveProfiles("test") @Import({RenewalPersistence.class,RenewalPersistenceTests.JsonConfig.class})
class RenewalPersistenceTests {
    @TestConfiguration static class JsonConfig {@Bean ObjectMapper mapper(){return new ObjectMapper();}}
    @Autowired EntityManager em;
    @Autowired RenewalPersistence store;
    private static final Instant NOW=Instant.parse("2026-10-06T00:00:00Z");

    @Test void quotePersistsImmutableSnapshotAndExpiryIsExclusive() {
        var rental=fixture();var quote=store.storeQuote(rental,rental.getCustomer(),Map.of("source","test-only-authoritative-fixture","amount",123),NOW,NOW.plusSeconds(1800));
        em.flush();UUID id=quote.getId();em.clear();var actualRental=em.find(Rental.class,rental.getId());
        var result=store.validQuote(id,actualRental,actualRental.getCustomer(),NOW.plusSeconds(1799));
        assertThat(result.getTermsJson()).contains("test-only-authoritative-fixture");
        assertThat(result.getTermsHash()).hasSize(64);
        assertThatThrownBy(()->store.validQuote(id,actualRental,actualRental.getCustomer(),NOW.plusSeconds(1800))).isInstanceOf(ApiException.class);
        var other=new User();other.setEmail("other@test.invalid");other.setFullName("Other");other.setPasswordHash("test");em.persist(other);
        assertThatThrownBy(()->store.validQuote(id,actualRental,other,NOW)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(404));
    }
    @Test void acceptedRevisionKeepsHistoryAndVersionWithoutChangingRentalEnd() {
        var rental=fixture();LocalDate oldEnd=rental.getContractEndDate();var locked=store.lockRental(rental.getId());
        var q1=store.storeQuote(locked,locked.getCustomer(),Map.of("rate",100),NOW,NOW.plusSeconds(1800));
        var renewal=request(locked);var flow=store.acceptInitial(locked,renewal,q1,NOW,"first");long version=flow.getVersion();
        var q2=store.storeQuote(locked,locked.getCustomer(),Map.of("rate",200),NOW,NOW.plusSeconds(1800));
        store.acceptRevision(store.lockWorkflow(renewal.getId(),version),q2,NOW.plusSeconds(1),"second");
        em.flush();assertThat(flow.getVersion()).isGreaterThan(version);
        assertThat(flow.getAcceptedRevision().getRevisionNumber()).isEqualTo(2);
        assertThat(em.createQuery("select count(x) from RenewalAcceptedRevision x",Long.class).getSingleResult()).isEqualTo(2);
        assertThat(rental.getContractEndDate()).isEqualTo(oldEnd);
        assertThatThrownBy(()->store.lockWorkflow(renewal.getId(),version)).isInstanceOf(ApiException.class);
    }
    @Test void duplicateOpenAndNonterminalReleaseAreBlocked() {
        var rental=store.lockRental(fixture().getId());
        var q=store.storeQuote(rental,rental.getCustomer(),Map.of("rate",100),NOW,NOW.plusSeconds(1800));
        var first=request(rental);var flow=store.acceptInitial(rental,first,q,NOW,null);
        var q2=store.storeQuote(rental,rental.getCustomer(),Map.of("rate",200),NOW,NOW.plusSeconds(1800));var second=request(rental);
        assertThatThrownBy(()->store.acceptInitial(rental,second,q2,NOW,null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->store.releaseSlot(flow)).isInstanceOf(ApiException.class);
        first.setStatus(RenewalStatus.cancelled);store.releaseSlot(flow);em.flush();
        assertThat(em.find(RenewalOpenSlot.class,rental.getId())).isNull();
        assertThat(em.find(Renewal.class,first.getId())).isNotNull();
    }
    @Test void idempotencyIsDurableCanonicalAndBoundToActorOperationResource() {
        var rental=fixture();var actor=rental.getCustomer();UUID resource=rental.getId();
        var payload=new LinkedHashMap<String,Object>();payload.put("b",2);payload.put("a",Map.of("z",3,"a",1));
        store.remember(actor,"submit","key",resource,payload,201,Map.of("id",resource.toString()));em.flush();em.clear();
        var user=em.find(User.class,actor.getId());
        var result=store.replay(user,"submit","key",resource,Map.of("a",Map.of("a",1,"z",3),"b",2)).orElseThrow();
        assertThat(result.httpStatus()).isEqualTo(201);assertThat(result.dataJson()).contains(resource.toString());
        assertThat(store.replay(user,"cancel","key",resource,payload)).isEmpty();
        assertThatThrownBy(()->store.replay(user,"submit","key",UUID.randomUUID(),payload)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->store.replay(user,"submit","key",resource,Map.of("b",99))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->store.remember(user,"submit","x",resource,payload,409,Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void databaseEnforcesIdempotencyUniqueness() {
        var rental=fixture();var actor=rental.getCustomer();
        store.remember(actor,"submit","key",rental.getId(),Map.of("a",1),201,Map.of("ok",true));
        assertThatThrownBy(()->store.remember(actor,"submit","key",rental.getId(),Map.of("a",1),201,Map.of("ok",true))).isInstanceOf(RuntimeException.class);
    }
    @Test void legacyRenewalHasNoFabricatedVersion() {
        var renewal=request(fixture());em.flush();
        assertThatThrownBy(()->store.lockWorkflow(renewal.getId(),0)).isInstanceOf(ApiException.class);
    }
    private Renewal request(Rental rental) {var n=new Renewal();n.setRental(rental);n.setRequestedBy(rental.getCustomer());n.setNewEndDate(rental.getContractEndDate().plusMonths(1));n.setAmount(new BigDecimal("100"));em.persist(n);return n;}
    // Isolated H2 fixtures, not runtime data or defaults.
    private Rental fixture() {
        Facility f=new Facility();f.setCode("TEST");f.setName("Test");f.setAddress("Test");f.setCity("Test");em.persist(f);
        User u=new User();u.setEmail("fixture@test.invalid");u.setFullName("Test Customer");u.setPasswordHash("test-only");em.persist(u);
        UnitType t=new UnitType();t.setFacility(f);t.setCode("M");t.setName("Medium");t.setLengthM(BigDecimal.ONE);t.setWidthM(BigDecimal.ONE);t.setHeightM(BigDecimal.ONE);t.setMonthlyPrice(new BigDecimal("100"));t.setMaxLoadKg(BigDecimal.ONE);t.setRackLengthM(BigDecimal.ONE);t.setRackWidthM(BigDecimal.ONE);t.setRackHeightM(BigDecimal.ONE);em.persist(t);
        StorageUnit unit=new StorageUnit();unit.setFacility(f);unit.setUnitType(t);unit.setCode("TEST-1");em.persist(unit);
        ReservationQuote q=new ReservationQuote();q.setCustomer(u);q.setFacility(f);q.setUnitType(t);q.setPricingPackageCode("TEST");q.setPolicyVersion("test-only");q.setStartDate(LocalDate.of(2026,10,1));q.setEndDate(LocalDate.of(2026,11,1));q.setRentalMonths(1);q.setMonthlyPrice(new BigDecimal("100"));q.setSubtotal(new BigDecimal("100"));q.setDiscountRate(BigDecimal.ZERO);q.setDiscountAmount(BigDecimal.ZERO);q.setTotalAfterDiscount(new BigDecimal("100"));q.setReservationDepositAmount(BigDecimal.ZERO);q.setSecurityDepositAmount(BigDecimal.ZERO);q.setRemainingRentalAmount(new BigDecimal("100"));q.setDueAtCheckIn(new BigDecimal("100"));q.setTotalInitialObligation(new BigDecimal("100"));q.setQuotedAt(NOW);q.setExpiresAt(NOW.plusSeconds(1800));em.persist(q);
        Reservation res=new Reservation();res.setReservationCode("TEST");res.setCustomer(u);res.setSourceQuote(q);res.setIdempotencyKey("TEST");res.setFacility(f);res.setUnitType(t);res.setStartDate(q.getStartDate());res.setEndDate(q.getEndDate());em.persist(res);
        Rental r=new Rental();r.setCustomer(u);r.setFacility(f);r.setStorageUnit(unit);r.setReservation(res);r.setStartDate(q.getStartDate());r.setContractEndDate(LocalDate.of(2026,10,31));r.setMonthlyPrice(new BigDecimal("100"));em.persist(r);em.flush();return r;
    }
}
