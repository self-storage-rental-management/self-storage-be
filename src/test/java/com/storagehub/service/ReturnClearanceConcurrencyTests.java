package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.returns.ReturnInspectionRequest;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.ledger.ReturnFinancialReconciliation;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ReturnClearanceConcurrencyTests {
    @Autowired EntityManager em;
    @Autowired ReturnCaseRepository returns;
    @Autowired RentalRepository rentals;
    @Autowired StorageUnitRepository units;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactions;
    @Autowired javax.sql.DataSource dataSource;

    @org.junit.jupiter.api.BeforeEach
    void provisionDisplayCodeTablesUsedByEntityFormulas() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("create table if not exists business_record_codes (resource_key varchar(120) primary key, display_code varchar(16))");
        jdbc.execute("create table if not exists money_receipt_codes (source_type varchar(24), source_id binary(16), receipt_code varchar(20))");
        jdbc.execute("create alias if not exists bin_to_uuid for 'com.storagehub.service.ReturnClearanceConcurrencyTests.binaryUuid'");
    }

    public static String binaryUuid(byte[] value) {
        if (value == null) return null;
        var buffer = java.nio.ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    record Fixture(UUID id, UUID image, ActorPrincipal customer, ActorPrincipal staff) {}

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void onlyFirstActionCommitsWhenFinalizeAndRevokeOverlap(boolean revokeFirst) throws Exception {
        var tx = new TransactionTemplate(transactions);
        var service = new ReturnSettlementService(returns, rentals, units, users, mock(AdminAuthorizationService.class),
            mock(FacilityScopeService.class), mock(NotificationService.class), mock(AuditLogService.class), new ObjectMapper());
        ReflectionTestUtils.setField(service, "settlementEntityManager", em);
        var pallets = mock(PalletInventoryService.class);
        when(pallets.equipment(any())).thenReturn(new com.storagehub.api.checkin.CheckInResponse.Equipment(1, 1, 2));
        ReflectionTestUtils.setField(service, "palletInventoryService", pallets);
        var finance = mock(ReturnFinancialReconciliation.class);
        when(finance.capture(any())).thenReturn(new ReturnFinancialReconciliation.View(
            BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, 1L, BigDecimal.ZERO));
        var beans = new StaticListableBeanFactory();
        beans.addBean("finance", finance);
        ReflectionTestUtils.setField(service, "returnFinances", beans.getBeanProvider(ReturnFinancialReconciliation.class));
        var data = tx.execute(status -> {
            String suffix = revokeFirst ? "1" : "2";
            var rental = fixture("RC" + suffix, "UNIT" + suffix, "Customer", LocalDate.of(2026, 11, 30));
            var staff = new User();
            staff.setEmail("staff" + suffix + "@test.invalid");
            staff.setFullName("Inspector");
            staff.setPasswordHash("test");
            em.persist(staff);
            var rc = new ReturnCase();
            rc.setRental(rental);
            rc.setFacility(rental.getFacility());
            rc.setStorageUnit(rental.getStorageUnit());
            rc.setCustomer(rental.getCustomer());
            rc.setRequestedBy(rental.getCustomer());
            rc.setRequestedAt(Instant.now());
            rc.setScheduledDate(rental.getContractEndDate());
            rc.setReturnArrivedAt(Instant.now());
            rc.setReturnIdentityVerifiedAt(rc.getReturnArrivedAt());
            rc.setDepositAmount(BigDecimal.TEN);
            em.persist(rc);
            var photo = new FileAsset();
            photo.setUploadedBy(staff);
            photo.setStorageKey("photo" + suffix);
            photo.setOriginalName("photo.jpg");
            photo.setContentType("image/jpeg");
            photo.setChecksumSha256("0".repeat(64));
            photo.setEntityType("RETURN_CASE");
            photo.setEntityId(rc.getId());
            em.persist(photo);
            return new Fixture(rc.getId(), photo.getId(),
                new ActorPrincipal(rental.getCustomer().getId(), UUID.randomUUID(), Set.of(RoleCode.CUSTOMER), Set.of(), Map.of()),
                new ActorPrincipal(staff.getId(), UUID.randomUUID(), Set.of(RoleCode.STAFF), Set.of("returns:process"), Map.of()));
        });
        tx.executeWithoutResult(status -> service.setCustomerClearance(data.customer(), data.id(), true, 0));
        assertThat(tx.execute(status -> service.getReturnForCustomer(data.customer(), data.id())).customerClearedAt()).isNotNull();
        var inspection = new ReturnInspectionRequest(InventoryMatch.match, DamageClassification.no_damage, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "Checked", List.of(data.image().toString()),
            true, true, true, StorageUnitStatus.available, true);
        Runnable finalize = () -> service.performInspection(data.staff(), data.id(), inspection, 0);
        Runnable revoke = () -> service.setCustomerClearance(data.customer(), data.id(), false, 0);
        var loaded = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var loser = pool.submit(() -> tx.executeWithoutResult(status -> {
                // Load the old entity into this transaction's first-level cache.
                returns.findById(data.id()).orElseThrow();
                loaded.countDown();
                await(committed);
                (revokeFirst ? finalize : revoke).run();
            }));
            await(loaded);
            try {
                tx.executeWithoutResult(status -> (revokeFirst ? revoke : finalize).run());
            } finally {
                committed.countDown();
            }
            assertThatThrownBy(() -> loser.get(15, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
        }
        var result = tx.execute(status -> service.getReturnForCustomer(data.customer(), data.id()));
        assertThat(result.inspectedAt() != null).isEqualTo(!revokeFirst);
        assertThat(result.customerClearedAt() != null).isEqualTo(!revokeFirst);
        assertThat(result.status()).isEqualTo(revokeFirst ? ReturnCaseStatus.requested : ReturnCaseStatus.awaiting_customer_confirmation);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

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
