package com.storagehub.service.renewal.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorContext;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.renewal.*;
import com.storagehub.service.renewal.persistence.RenewalPersistence;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** All records here exist ONLY in the rollback-isolated H2 test database. */
@DataJpaTest
@ActiveProfiles("test")
@Import({SharedCatalogRenewalPricingSource.class, RenewalWorkflowService.class, RenewalReadService.class,
    RenewalPersistence.class, AuditLogService.class, SharedCatalogRenewalPricingJpaTests.TestBeans.class})
class SharedCatalogRenewalPricingJpaTests {
    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    @TestConfiguration
    static class TestBeans {
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean ActorContext actorContext() { return mock(ActorContext.class); }
        @Bean com.storagehub.service.RentalReadSources.DateSource dates() {
            return r -> java.util.Optional.of(new com.storagehub.service.RentalReadSources.Dates(r.getId(),r.getStartDate(),r.getContractEndDate(),"TEST-ONLY-inclusive-proof"));
        }
        @Bean com.storagehub.service.rental.period.RentalPeriodResolver periods(org.springframework.beans.factory.ObjectProvider<com.storagehub.service.RentalReadSources.DateSource> sources) {
            return new com.storagehub.service.rental.period.RentalPeriodResolver(sources);
        }
    }

    @Autowired EntityManager em;
    @Autowired SharedCatalogRenewalPricingSource source;
    @Autowired RenewalWorkflowService workflow;
    @Autowired ApplicationContext context;

    @Test void loadsRealCatalogRowsAfterReloadWithoutChangingSharedData() {
        var rental = fixture();
        var pack = persistPackage(rental.getFacility(), "THREE", 3, new BigDecimal("0.1000"));
        UUID id = rental.getId();
        em.clear();
        var current = em.find(Rental.class, id);
        // Compare DB timestamps to DB timestamps (the schema stores microseconds, not JVM nanoseconds).
        var rentalUpdated = current.getUpdatedAt();
        var packageUpdated = em.find(RentalPackagePolicy.class, pack.getId()).getUpdatedAt();
        var price = source.read(current, START).orElseThrow().getFirst();
        assertThat(price.monthlyPrice()).isEqualByComparingTo("5500000.00");
        assertThat(price.discountRate()).isEqualByComparingTo("0.10");
        assertThat(price.packageId()).isEqualTo(pack.getId());
        assertThat(price.packageVersion()).isEqualTo("test-only-v1");
        assertThat(price.months()).isEqualTo(3);
        em.flush(); em.clear();
        assertThat(em.find(Rental.class, id).getMonthlyPrice()).isEqualByComparingTo("100.00");
        assertThat(em.find(Rental.class, id).getUpdatedAt()).isEqualTo(rentalUpdated);
        assertThat(em.find(RentalPackagePolicy.class, pack.getId()).getUpdatedAt()).isEqualTo(packageUpdated);
    }

    @Test void readsUpdatedCatalogRateAndVersionNotDetachedRentalSnapshot() {
        var rental = fixture();
        var pack = persistPackage(rental.getFacility(), "BASE", 1, BigDecimal.ZERO);
        UUID typeId = rental.getStorageUnit().getUnitType().getId();
        em.clear();
        var currentType = em.find(UnitType.class, typeId);
        currentType.setMonthlyPrice(new BigDecimal("6000000.00"));
        var currentPack = em.find(RentalPackagePolicy.class, pack.getId());
        currentPack.setPolicyVersion("test-only-v2");
        em.flush(); em.clear();
        var price = source.read(rental, START).orElseThrow().getFirst();
        assertThat(price.monthlyPrice()).isEqualByComparingTo("6000000.00");
        assertThat(price.packageVersion()).isEqualTo("test-only-v2");
        assertThat(rental.getMonthlyPrice()).isEqualByComparingTo("100.00");
    }

    @Test void catalogIsFacilityScopedAndEvaluatesFutureExtensionDate() {
        var rental = fixture();
        var own = persistPackage(rental.getFacility(), "BASE", 1, BigDecimal.ZERO);
        own.setEffectiveFrom(START); own.setEffectiveTo(START);
        var another = facility("OTHER");
        persistPackage(another, "OTHER", 6, new BigDecimal("0.2"));
        var expired = persistPackage(rental.getFacility(), "EXPIRED", 2, BigDecimal.ZERO);
        expired.setEffectiveTo(START.minusDays(1));
        var inactive = persistPackage(rental.getFacility(), "INACTIVE", 4, BigDecimal.ZERO);
        inactive.setActive(false);
        em.flush(); em.clear();
        var prices = source.read(em.find(Rental.class, rental.getId()), START).orElseThrow();
        assertThat(prices).extracting(p -> p.packageCode()).containsExactly("BASE");
    }

    @Test void emptyDatabaseCatalogDoesNotCreateDefaults() {
        var rental = fixture();
        assertThat(source.read(rental, START)).hasValueSatisfying(prices -> assertThat(prices).isEmpty());
        assertThat(em.createQuery("select count(p) from RentalPackagePolicy p", Long.class).getSingleResult()).isZero();
    }

    @Test void realPricingBeanCannotBypassMissingBoPolicyOrEnableRenewal() {
        var rental = fixture();
        persistPackage(rental.getFacility(), "BASE", 1, BigDecimal.ZERO);
        var actor = new ActorPrincipal(rental.getCustomer().getId(), UUID.randomUUID(),
            Set.of(RoleCode.CUSTOMER), Set.of(), Map.of());
        assertThat(context.getBeansOfType(RenewalSources.PricingSource.class)).hasSize(1);
        assertThat(context.getBeansOfType(RenewalSources.PolicySource.class)).isEmpty();
        assertThat(context.getBeansOfType(RenewalSources.FinancialSource.class)).isEmpty();
        assertThat(context.getBeansOfType(RenewalSources.ExtensionHoldSource.class)).isEmpty();
        assertThatThrownBy(() -> workflow.options(actor, rental.getId())).isInstanceOf(ApiException.class)
            .hasMessageContaining("DEFERRED_SOURCE: BO renewal policy missing");
        assertThat(em.createQuery("select count(q) from RenewalQuote q", Long.class).getSingleResult()).isZero();
        assertThat(rental.getContractEndDate()).isEqualTo(START.minusDays(1));
    }

    private Facility facility(String code) {
        var f = new Facility(); f.setCode(code); f.setName("H2-only facility");
        f.setAddress("H2-only"); f.setCity("H2-only"); em.persist(f); return f;
    }

    private RentalPackagePolicy persistPackage(Facility facility, String code, int months, BigDecimal discount) {
        var p = new RentalPackagePolicy(); p.setFacility(facility); p.setCode(code);
        p.setName("H2-only package"); p.setRentalMonths(months); p.setDiscountRate(discount);
        p.setPolicyVersion("test-only-v1"); p.setEffectiveFrom(START.minusMonths(1));
        em.persist(p); em.flush(); return p;
    }

    private Rental fixture() {
        var f = facility("TEST");
        var u = new User(); u.setEmail("pricing-fixture@test.invalid"); u.setFullName("H2-only customer");
        u.setPasswordHash("test-only"); em.persist(u);
        var t = new UnitType(); t.setFacility(f); t.setCode("S"); t.setName("H2-only type");
        t.setLengthM(BigDecimal.ONE); t.setWidthM(BigDecimal.ONE); t.setHeightM(BigDecimal.ONE);
        t.setMonthlyPrice(new BigDecimal("5500000.00")); t.setMaxLoadKg(BigDecimal.ONE);
        t.setRackLengthM(BigDecimal.ONE); t.setRackWidthM(BigDecimal.ONE); t.setRackHeightM(BigDecimal.ONE); em.persist(t);
        var unit = new StorageUnit(); unit.setFacility(f); unit.setUnitType(t); unit.setCode("H2-TEST-1"); em.persist(unit);
        var q = new ReservationQuote(); q.setCustomer(u); q.setFacility(f); q.setUnitType(t);
        q.setPricingPackageCode("TEST"); q.setPolicyVersion("test-only"); q.setStartDate(START.minusMonths(1));
        q.setEndDate(START); q.setRentalMonths(1); q.setMonthlyPrice(new BigDecimal("100.00"));
        q.setSubtotal(new BigDecimal("100.00")); q.setDiscountRate(BigDecimal.ZERO); q.setDiscountAmount(BigDecimal.ZERO);
        q.setTotalAfterDiscount(new BigDecimal("100.00")); q.setReservationDepositAmount(BigDecimal.ZERO);
        q.setSecurityDepositAmount(BigDecimal.ZERO); q.setRemainingRentalAmount(new BigDecimal("100.00"));
        q.setDueAtCheckIn(new BigDecimal("100.00")); q.setTotalInitialObligation(new BigDecimal("100.00"));
        q.setQuotedAt(Instant.parse("2026-10-01T00:00:00Z")); q.setExpiresAt(q.getQuotedAt().plusSeconds(1800)); em.persist(q);
        var reservation = new Reservation(); reservation.setReservationCode("H2-TEST"); reservation.setCustomer(u);
        reservation.setSourceQuote(q); reservation.setIdempotencyKey("H2-TEST"); reservation.setFacility(f);
        reservation.setUnitType(t); reservation.setStartDate(q.getStartDate()); reservation.setEndDate(q.getEndDate()); em.persist(reservation);
        var rental = new Rental(); rental.setCustomer(u); rental.setFacility(f); rental.setStorageUnit(unit);
        rental.setReservation(reservation); rental.setStartDate(q.getStartDate()); rental.setContractEndDate(START.minusDays(1));
        rental.setMonthlyPrice(new BigDecimal("100.00")); em.persist(rental); em.flush(); return rental;
    }
}
