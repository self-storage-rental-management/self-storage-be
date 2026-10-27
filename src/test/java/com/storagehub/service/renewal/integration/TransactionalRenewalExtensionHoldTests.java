package com.storagehub.service.renewal.integration;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.domain.model.*;
import com.storagehub.service.RentalReadSources;
import com.storagehub.service.rental.period.*;
import com.storagehub.service.renewal.RenewalSources;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Isolated H2 only. TEST-ONLY protocol does not certify production cross-writer coverage. */
@DataJpaTest(properties={"storagehub.integration.renewal-operational-checks.enabled=true","storagehub.integration.renewal-extension-holds.enabled=true",
    "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=BINARY"})
@ActiveProfiles("test") @Import({TransactionalRenewalExtensionHoldSource.class,RenewalOperationalGuard.class,TransactionalRenewalExtensionHoldTests.Sources.class})
class TransactionalRenewalExtensionHoldTests {
    static final Instant NOW=Instant.parse("2026-10-11T00:00:00Z");
    static final LocalDate START=LocalDate.of(2026,11,2),END=LocalDate.of(2026,12,2);
    static final Instant EXPIRY=NOW.plusSeconds(86400),CUTOFF=NOW.plusSeconds(432000);
    @TestConfiguration static class Sources {
        @Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}
        @Bean MutableClock clock(){return new MutableClock();}
        @Bean TestProtocol protocol(){return new TestProtocol();}
        @Bean RentalReadSources.DateSource dates(){return r->Optional.of(new RentalReadSources.Dates(r.getId(),r.getStartDate(),r.getContractEndDate(),"TEST-ONLY"));}
        @Bean RentalPeriodResolver periods(ObjectProvider<RentalReadSources.DateSource> dates){return new RentalPeriodResolver(dates);}
    }
    static class MutableClock extends Clock {Instant time=NOW;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return Clock.fixed(time,z);}public Instant instant(){return time;}}
    static class TestProtocol implements RenewalSharedWriterProtocol {volatile boolean available=true;
        public boolean ready(){return available;}
        public Instant lockAndCheck(Rental r,LocalDate s,LocalDate e,Instant at){return CUTOFF;}}
    @Autowired EntityManager em;@Autowired JdbcTemplate jdbc;@Autowired DataSource ds;
    @Autowired TransactionalRenewalExtensionHoldSource holds;@Autowired MutableClock clock;@Autowired TestProtocol protocol;
    @Autowired PlatformTransactionManager transactions;
    java.sql.Connection schemaConnection;
    @BeforeEach void schema() throws java.sql.SQLException {
        clock.time=NOW;protocol.available=true;
        jdbc.execute("drop table if exists renewal_extension_holds");
        // H2 2.4 caches the IN-constraint comparator's creation session. Keep that session
        // alive across the multi-connection test; do not remove/relax the production constraint.
        schemaConnection=ds.getConnection();
        new ResourceDatabasePopulator(new ClassPathResource("db/renewal-hold-migration/V2026101104__create_renewal_extension_holds.sql")).populate(schemaConnection);
    }
    @AfterEach void closeSchemaSession() throws java.sql.SQLException {if(schemaConnection!=null)schemaConnection.close();}
    @Test void acquirePersistsBoundReferenceAndSamePayloadReplayReturnsSameId() {
        var n=fixture();var id=holds.acquire(n.getRental(),n.getId(),START,END,EXPIRY);
        assertThat(holds.acquire(n.getRental(),n.getId(),START,END,EXPIRY)).isEqualTo(id);
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state from renewal_extension_holds",String.class)).isEqualTo("ACTIVE");
        assertThat(n.getRental().getStorageUnit().getStatus()).isEqualTo(StorageUnitStatus.occupied);
        assertThat(n.getRental().getContractEndDate()).isEqualTo(LocalDate.of(2026,11,1));
    }
    @Test void auditedDeadlineCorrectionCanRestoreReleasedHoldButNotConsumedHold() {
        var n=fixture();var ref=holds.acquire(n.getRental(),n.getId(),START,END,EXPIRY);
        var terms=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().convertValue(Map.of(
            "extensionStartDate",START,"extensionEndExclusive",END),com.storagehub.api.renewal.RenewalQuoteResponse.Terms.class);
        holds.release(n,ref);clock.time=EXPIRY.plusSeconds(1);
        assertThat(holds.restoreSigningWindow(n,terms,ref,clock.time.plusSeconds(600))).isTrue();
        assertThat(jdbc.queryForObject("select state from renewal_extension_holds",String.class)).isEqualTo("ACTIVE");
        holds.consume(n,ref);
        assertThatThrownBy(()->holds.restoreSigningWindow(n,terms,ref,clock.time.plusSeconds(600))).hasMessageContaining("Consumed hold");
    }
    @Test void changedRetryPayloadFailsWithoutAdditionalHold() {
        var n=fixture();holds.acquire(n.getRental(),n.getId(),START,END,EXPIRY);
        assertThatThrownBy(()->holds.acquire(n.getRental(),n.getId(),START,END.plusDays(1),EXPIRY)).hasMessageContaining("retry payload changed");
        assertThat(count()).isEqualTo(1);
    }
    @Test void timestampPrecisionMatchesMysqlMicrosecondsForReplay() {
        var n=fixture();var expiry=EXPIRY.plusNanos(123456789);
        var ref=holds.acquire(n.getRental(),n.getId(),START,END,expiry);
        assertThat(holds.acquire(n.getRental(),n.getId(),START,END,expiry)).isEqualTo(ref);
    }
    @Test void d3SafetyRechecksPersistedHoldTermsExpiryAndOperationalConflicts() {
        var n=fixture();var ref=holds.acquire(n.getRental(),n.getId(),START,END,EXPIRY);
        var terms=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().convertValue(Map.of(
            "oldEndDate",n.getRental().getContractEndDate(),"storageUnitId",n.getRental().getStorageUnit().getId(),
            "facilityId",n.getRental().getFacility().getId(),"extensionStartDate",START,"extensionEndExclusive",END),com.storagehub.api.renewal.RenewalQuoteResponse.Terms.class);
        assertThat(holds.check(n,terms,ref,NOW)).isEqualTo(CUTOFF);
        assertThatThrownBy(()->holds.check(n,terms,ref,EXPIRY.plusNanos(1000))).hasMessageContaining("expired");
        n.getRental().setActualReturnedAt(NOW);em.flush();
        assertThatThrownBy(()->holds.check(n,terms,ref,NOW)).hasMessageContaining("non-active");
        assertThat(jdbc.queryForObject("select state from renewal_extension_holds",String.class)).isEqualTo("ACTIVE");
    }
    @Test void competingRenewalCannotOverlapEvenAfterFirstHoldExpiresWithoutReconciliation() {
        var n=fixture();holds.acquire(n.getRental(),n.getId(),START,END,EXPIRY);
        clock.time=EXPIRY.plusSeconds(1);var other=renewal(n.getRental());
        assertThatThrownBy(()->holds.acquire(other.getRental(),other.getId(),START,END,clock.time.plusSeconds(60))).hasMessageContaining("Conflicting extension hold");
        assertThat(count()).isEqualTo(1);
    }
    @Test void lifecycleRetainConsumeReplayAndTerminalReleaseAreBound() {
        var n=fixture();var ref=holds.acquire(n.getRental(),n.getId(),START,END,EXPIRY);
        holds.retain(n,ref,EXPIRY.plusSeconds(600));
        holds.retain(n,ref,EXPIRY.plusSeconds(600));
        assertThat(jdbc.queryForObject("select version from renewal_extension_holds",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select expires_at from renewal_extension_holds",java.sql.Timestamp.class).toInstant()).isEqualTo(EXPIRY.plusSeconds(600));
        holds.consume(n,ref);holds.consume(n,ref);
        assertThat(jdbc.queryForObject("select state from renewal_extension_holds",String.class)).isEqualTo("CONSUMED");
        assertThatThrownBy(()->holds.release(n,ref)).hasMessageContaining("cannot be released");
    }
    @Test void explicitReleaseIsIdempotentAndNeverReopensOldReference() {
        var n=fixture();var ref=holds.acquire(n.getRental(),n.getId(),START,END,EXPIRY);
        holds.release(n,ref);holds.release(n,ref);
        assertThat(jdbc.queryForObject("select version from renewal_extension_holds",Long.class)).isEqualTo(1);
        assertThatThrownBy(()->holds.acquire(n.getRental(),n.getId(),START,END,EXPIRY)).hasMessageContaining("inactive or expired");
    }
    @Test void foreignReferenceCannotBeReleasedOrConsumed() {
        var n=fixture();var ref=holds.acquire(n.getRental(),n.getId(),START,END,EXPIRY);var other=renewal(n.getRental());
        assertThatThrownBy(()->holds.release(other,ref)).hasMessageContaining("another rental/renewal/unit");
        assertThatThrownBy(()->holds.consume(other,ref)).hasMessageContaining("another rental/renewal/unit");
    }
    @Test void missingProtocolNeverAdvertisesAtomicityOrWrites() {
        var n=fixture();protocol.available=false;
        assertThat(holds.atomic()).isFalse();assertThat(holds.participatesInTransaction()).isFalse();
        assertThatThrownBy(()->holds.acquire(n.getRental(),n.getId(),START,END,EXPIRY)).hasMessageContaining("DEFERRED_SOURCE");
        assertThat(count()).isZero();
    }
    @Test void invalidExpiryAndReturnedRentalBlockWithoutHold() {
        var n=fixture();
        assertThatThrownBy(()->holds.acquire(n.getRental(),n.getId(),START,END,CUTOFF.plusSeconds(1))).hasMessageContaining("expiry outside");
        n.getRental().setActualReturnedAt(NOW);em.flush();
        assertThatThrownBy(()->holds.acquire(n.getRental(),n.getId(),START,END,EXPIRY)).hasMessageContaining("non-active");
        assertThat(count()).isZero();
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void callerRollbackUndoesJdbcHoldAndJpaRenewalTogether() {
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(s->{
            var n=fixture();holds.acquire(n.getRental(),n.getId(),START,END,EXPIRY);assertThat(count()).isEqualTo(1);
            throw new IllegalStateException("TEST-ONLY failure after hold");
        })).hasMessageContaining("failure after hold");
        assertThat(count()).isZero();
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void cannotRunOutsideCallerTransaction() {
        assertThatThrownBy(()->holds.acquire(new Rental(),UUID.randomUUID(),START,END,EXPIRY)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count()).isZero();
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentAcquiresSerializeOnRentalAndUnitAndRejectSecondOverlap() throws Exception {
        var ids=new TransactionTemplate(transactions).execute(s->{var first=fixture();var second=renewal(first.getRental());return List.of(first.getId(),second.getId());});
        var locked=new CountDownLatch(1);var secondStarted=new CountDownLatch(1);var release=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(()->new TransactionTemplate(transactions).execute(s->{
                var n=em.find(Renewal.class,ids.getFirst());var ref=holds.acquire(n.getRental(),n.getId(),START,END,EXPIRY);locked.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("TEST lock timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return ref;
            }));
            var acquired=locked.await(10,TimeUnit.SECONDS);
            if(!acquired&&first.isDone())first.get(1,TimeUnit.SECONDS);
            assertThat(acquired).isTrue();
            var second=executor.submit(()->new TransactionTemplate(transactions).execute(s->{
                var n=em.find(Renewal.class,ids.get(1));secondStarted.countDown();return holds.acquire(n.getRental(),n.getId(),START,END,EXPIRY);
            }));
            assertThat(secondStarted.await(10,TimeUnit.SECONDS)).isTrue();assertThat(second.isDone()).isFalse();
            release.countDown();assertThat(first.get(10,TimeUnit.SECONDS)).isNotNull();
            assertThatThrownBy(()->second.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(com.storagehub.common.api.ApiException.class);
            assertThat(count()).isEqualTo(1);
        }finally{release.countDown();executor.shutdownNow();}
    }
    long count(){return jdbc.queryForObject("select count(*) from renewal_extension_holds",Long.class);}
    private Renewal renewal(Rental r){var n=new Renewal();n.setRental(r);n.setRequestedBy(r.getCustomer());n.setNewEndDate(END.minusDays(1));n.setAmount(BigDecimal.ONE);em.persist(n);em.flush();return n;}
    private Renewal fixture() {
        var code=UUID.randomUUID().toString().substring(0,8);
        var f=new Facility();f.setCode(code);f.setName("TEST");f.setAddress("TEST");f.setCity("TEST");em.persist(f);
        var u=new User();u.setEmail(code+"@test.invalid");u.setFullName("TEST");u.setPasswordHash("TEST-ONLY");em.persist(u);
        var t=new UnitType();t.setFacility(f);t.setCode("S");t.setName("TEST");t.setLengthM(BigDecimal.ONE);t.setWidthM(BigDecimal.ONE);
        t.setHeightM(BigDecimal.ONE);t.setMonthlyPrice(BigDecimal.ONE);t.setMaxLoadKg(BigDecimal.ONE);t.setRackLengthM(BigDecimal.ONE);
        t.setRackWidthM(BigDecimal.ONE);t.setRackHeightM(BigDecimal.ONE);em.persist(t);
        var unit=new StorageUnit();unit.setFacility(f);unit.setUnitType(t);unit.setCode(code);unit.setStatus(StorageUnitStatus.occupied);em.persist(unit);
        var q=com.storagehub.testing.LegacyReservationQuoteFixture.create();q.setCustomer(u);q.setFacility(f);q.setUnitType(t);q.setPricingPackageCode("TEST");q.setPolicyVersion("TEST");
        q.setStartDate(LocalDate.of(2026,10,1));q.setEndDate(LocalDate.of(2026,11,1));q.setRentalMonths(1);q.setMonthlyPrice(BigDecimal.ONE);
        q.setSubtotal(BigDecimal.ONE);q.setDiscountRate(BigDecimal.ZERO);q.setDiscountAmount(BigDecimal.ZERO);q.setTotalAfterDiscount(BigDecimal.ONE);
        q.setReservationDepositAmount(BigDecimal.ZERO);q.setSecurityDepositAmount(BigDecimal.ZERO);q.setRemainingRentalAmount(BigDecimal.ONE);
        q.setDueAtCheckIn(BigDecimal.ONE);q.setTotalInitialObligation(BigDecimal.ONE);q.setQuotedAt(NOW);q.setExpiresAt(EXPIRY);em.persist(q);
        var res=new Reservation();res.setReservationCode(code);res.setIdempotencyKey(code);res.setCustomer(u);res.setSourceQuote(q);
        res.setFacility(f);res.setUnitType(t);res.setStartDate(q.getStartDate());res.setEndDate(q.getEndDate());res.setStatus(ReservationStatus.COMPLETED);em.persist(res);
        var r=new Rental();r.setCustomer(u);r.setFacility(f);r.setStorageUnit(unit);r.setReservation(res);r.setStartDate(q.getStartDate());
        r.setContractEndDate(q.getEndDate());r.setMonthlyPrice(BigDecimal.ONE);em.persist(r);em.flush();return renewal(r);
    }
}
