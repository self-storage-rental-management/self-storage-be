package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.CustomerReservationService;
import com.storagehub.service.NotificationService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** Real JPA/JDBC persistence for the existing Customer receipt activation flow, isolated in H2. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=${STORAGEHUB_LEDGER_E2E_DB_URL:jdbc:h2:mem:ledger-activation;MODE=MySQL;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${STORAGEHUB_LEDGER_E2E_DB_USERNAME:sa}",
    "spring.datasource.password=${STORAGEHUB_LEDGER_E2E_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=${STORAGEHUB_LEDGER_E2E_DDL_AUTO:create-drop}",
    "spring.sql.init.mode=${STORAGEHUB_LEDGER_E2E_SQL_INIT_MODE:always}",
    "spring.sql.init.schema-locations=${STORAGEHUB_LEDGER_E2E_SQL_SCHEMA:classpath:rental-ledger-test-schema.sql}",
    "spring.flyway.enabled=false",
    "storagehub.integration.ledger.enabled=true",
    "storagehub.integration.communication.enabled=false",
    "app.bootstrap-demo-data.enabled=false",
    "app.bootstrap-admin.enabled=false",
    "app.login-history.backfill-fingerprints=false",
    "app.reservation.expiration-check-ms=86400000",
    "app.reservation.expiration-initial-delay-ms=86400000",
    "app.support.auto-close.fixed-delay-ms=86400000",
    "app.support.auto-close.initial-delay-ms=86400000"
})
@ActiveProfiles("test")
@Transactional
@Import(CustomerReceiptLedgerActivationIntegrationTests.Collaborators.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CustomerReceiptLedgerActivationIntegrationTests {
    @TestConfiguration
    static class Collaborators {
        @Bean @Primary AuditLogService activationAuditLogService() { return mock(AuditLogService.class); }
        @Bean @Primary NotificationService activationNotificationService() { return mock(NotificationService.class); }
    }

    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired CustomerReservationService service;
    @Autowired RentalRepository rentals;
    @Autowired LedgerStore ledger;
    @MockitoBean(name = "seedSystemRoles") CommandLineRunner roleDataInitializer;
    @MockitoBean(name = "seedSystemSettings") CommandLineRunner systemSettingsInitializer;

    private record Fixture(UUID reservationId, ActorPrincipal actor, UUID customerId, UUID facilityId) {}

    @Test
    @Order(1)
    void readOnlyPreflightConfirmsDatabaseLedgerTablesAndFlywayHistoryBeforeFixtureWrites() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product.toLowerCase().contains("mysql")) {
                String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
                String serverVersion = jdbc.queryForObject("SELECT VERSION()", String.class);
                assertThat(database).isEqualTo("storagehub_local");
                var latest = jdbc.queryForMap(
                    "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1");
                assertThat(Boolean.parseBoolean(latest.get("success").toString())).isTrue();
                List<String> tables = jdbc.queryForList(
                    "SELECT LOWER(table_name) FROM information_schema.tables WHERE table_schema=DATABASE() " +
                        "AND table_name IN ('rental_ledger_accounts','rental_ledger_obligations','rental_ledger_receipts','rental_ledger_allocations','rental_ledger_refunds','rental_ledger_commands')",
                    String.class);
                assertThat(tables).containsExactlyInAnyOrder(
                    "rental_ledger_accounts", "rental_ledger_obligations", "rental_ledger_receipts",
                    "rental_ledger_allocations", "rental_ledger_refunds", "rental_ledger_commands");
                System.out.println("Ledger Slice 1 DB preflight PASS: database=" + database + "; mysql=" +
                    serverVersion + "; flyway=" + latest.get("version") + "; six ledger tables present");
            } else {
                assertThat(product.toLowerCase()).contains("h2");
                List<String> tables = jdbc.queryForList(
                    "SELECT LOWER(table_name) FROM information_schema.tables WHERE table_schema='PUBLIC' " +
                        "AND table_name IN ('RENTAL_LEDGER_ACCOUNTS','RENTAL_LEDGER_OBLIGATIONS','RENTAL_LEDGER_RECEIPTS','RENTAL_LEDGER_ALLOCATIONS','RENTAL_LEDGER_REFUNDS','RENTAL_LEDGER_COMMANDS')",
                    String.class);
                assertThat(tables).containsExactlyInAnyOrder(
                    "rental_ledger_accounts", "rental_ledger_obligations", "rental_ledger_receipts",
                    "rental_ledger_allocations", "rental_ledger_refunds", "rental_ledger_commands");
            }
        }
    }

    @Test
    @Order(2)
    void receiptActivationPersistsOneBoundLedgerAccountAndReplayHasNoAdditionalEffect() {
        Fixture fixture = fixture(ReservationStatus.AWAITING_CUSTOMER_RECEIPT, CheckInStatus.completed, false);

        var first = service.confirmReceipt(fixture.actor(), fixture.reservationId());
        UUID rentalId = em.createQuery("select r.id from Rental r where r.reservation.id=:reservation", UUID.class)
            .setParameter("reservation", fixture.reservationId()).getSingleResult();
        em.flush();
        em.clear();

        var account = ledger.read(rentalId).orElseThrow();
        assertThat(first.getReservation().getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(account.rentalId()).isEqualTo(rentalId);
        assertThat(account.customerId()).isEqualTo(fixture.customerId());
        assertThat(account.facilityId()).isEqualTo(fixture.facilityId());
        assertThat(jdbc.queryForObject("select count(*) from rental_ledger_accounts where rental_id=?", Integer.class,
            rentalId.toString())).isEqualTo(1);

        service.confirmReceipt(fixture.actor(), fixture.reservationId());
        em.flush();
        assertThat(jdbc.queryForObject("select count(*) from rental_ledger_accounts where rental_id=?", Integer.class,
            rentalId.toString())).isEqualTo(1);
    }

    @Test
    @Order(3)
    void incompleteHandoverAndCancelledReservationDoNotCreateRentalOrLedgerAccount() {
        Fixture pending = fixture(ReservationStatus.AWAITING_CUSTOMER_RECEIPT, CheckInStatus.scheduled, false);
        assertThatThrownBy(() -> service.confirmReceipt(pending.actor(), pending.reservationId()))
            .hasMessageContaining("handover is not completed");

        Fixture cancelled = fixture(ReservationStatus.CANCELLED, CheckInStatus.completed, false);
        assertThatThrownBy(() -> service.confirmReceipt(cancelled.actor(), cancelled.reservationId()))
            .hasMessageContaining("not waiting for customer receipt");

        assertThat(rentals.findByReservation_Id(pending.reservationId())).isEmpty();
        assertThat(rentals.findByReservation_Id(cancelled.reservationId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from rental_ledger_accounts", Integer.class)).isZero();
    }

    @Test
    @Order(4)
    void reusingAnExistingRentalDoesNotSilentlyBackfillItsLedger() {
        Fixture fixture = fixture(ReservationStatus.AWAITING_CUSTOMER_RECEIPT, CheckInStatus.completed, true);
        UUID existingRental = rentals.findByReservation_Id(fixture.reservationId()).orElseThrow().getId();

        service.confirmReceipt(fixture.actor(), fixture.reservationId());

        assertThat(ledger.read(existingRental)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from rental_ledger_accounts where rental_id=?", Integer.class,
            existingRental.toString())).isZero();
    }

    private Fixture fixture(ReservationStatus reservationStatus, CheckInStatus checkInStatus, boolean existingRental) {
        Facility facility = new Facility();
        facility.setCode("F-" + UUID.randomUUID().toString().substring(0, 8));
        facility.setName("Ledger activation test");
        facility.setAddress("Test address");
        facility.setCity("Test city");
        em.persist(facility);

        User customer = user("customer");
        User staff = user("staff");
        UnitType type = new UnitType();
        type.setFacility(facility);
        type.setCode("TYPE-" + UUID.randomUUID().toString().substring(0, 8));
        type.setName("Test unit");
        type.setLengthM(BigDecimal.ONE);
        type.setWidthM(BigDecimal.ONE);
        type.setHeightM(BigDecimal.ONE);
        type.setMonthlyPrice(new BigDecimal("100000"));
        type.setMaxLoadKg(new BigDecimal("100"));
        type.setRackCount(0);
        type.setRackLengthM(BigDecimal.ZERO);
        type.setRackWidthM(BigDecimal.ZERO);
        type.setRackHeightM(BigDecimal.ZERO);
        em.persist(type);

        ReservationQuote quote = new ReservationQuote();
        quote.setCustomer(customer);
        quote.setFacility(facility);
        quote.setUnitType(type);
        quote.setPricingPackageCode("TEST-1M");
        quote.setPolicyVersion("test-v1");
        quote.setStartDate(LocalDate.of(2026, 11, 1));
        quote.setEndDate(LocalDate.of(2026, 12, 1));
        quote.setRentalMonths(1);
        quote.setMonthlyPrice(new BigDecimal("100000"));
        quote.setSubtotal(new BigDecimal("100000"));
        quote.setDiscountRate(BigDecimal.ZERO);
        quote.setDiscountAmount(BigDecimal.ZERO);
        quote.setTotalAfterDiscount(new BigDecimal("100000"));
        quote.setReservationDepositAmount(BigDecimal.ZERO);
        quote.setSecurityDepositAmount(BigDecimal.ZERO);
        quote.setRemainingRentalAmount(new BigDecimal("100000"));
        quote.setDueAtCheckIn(new BigDecimal("100000"));
        quote.setTotalInitialObligation(new BigDecimal("100000"));
        quote.setQuotedAt(Instant.now());
        quote.setExpiresAt(Instant.now().plusSeconds(600));
        em.persist(quote);

        StorageUnit unit = new StorageUnit();
        unit.setFacility(facility);
        unit.setUnitType(type);
        unit.setCode("UNIT-" + UUID.randomUUID().toString().substring(0, 8));
        unit.setStatus(StorageUnitStatus.assigned);
        em.persist(unit);

        Reservation reservation = new Reservation();
        reservation.setReservationCode("RSV-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        reservation.setCustomer(customer);
        reservation.setSourceQuote(quote);
        reservation.setIdempotencyKey(UUID.randomUUID().toString());
        reservation.setFacility(facility);
        reservation.setUnitType(type);
        reservation.setAssignedUnit(unit);
        reservation.setStatus(reservationStatus);
        reservation.setGoodsReviewStatus(GoodsReviewStatus.NOT_REQUIRED);
        reservation.setCompatibilityResult(CompatibilityResult.COMPATIBLE);
        reservation.setStartDate(quote.getStartDate());
        reservation.setEndDate(quote.getEndDate());
        reservation.setAmount(new BigDecimal("100000"));
        reservation.setTotalGoodsVolumeM3(BigDecimal.ZERO);
        reservation.setTotalGoodsWeightKg(BigDecimal.ZERO);
        em.persist(reservation);

        ReservationPricingSnapshot snapshot = new ReservationPricingSnapshot();
        snapshot.setReservation(reservation);
        snapshot.setPricingPackageCode("TEST-1M");
        snapshot.setPricingPolicyVersion("test-v1");
        snapshot.setRentalMonths(1);
        snapshot.setMonthlyPrice(new BigDecimal("100000"));
        snapshot.setGrossRentalAmount(new BigDecimal("100000"));
        snapshot.setDiscountRate(BigDecimal.ZERO);
        snapshot.setDiscountAmount(BigDecimal.ZERO);
        snapshot.setNetRentalAmount(new BigDecimal("100000"));
        snapshot.setReservationDepositAmount(BigDecimal.ZERO);
        snapshot.setSecurityDepositAmount(BigDecimal.ZERO);
        snapshot.setRemainingRentalAmount(new BigDecimal("100000"));
        snapshot.setDueAtCheckIn(new BigDecimal("100000"));
        snapshot.setTotalInitialObligation(new BigDecimal("100000"));
        snapshot.setQuotedAt(Instant.now());
        snapshot.setExpiresAt(Instant.now().plusSeconds(600));
        em.persist(snapshot);

        Rental rental = null;
        if (existingRental) {
            rental = new Rental();
            rental.setCustomer(customer);
            rental.setFacility(facility);
            rental.setStorageUnit(unit);
            rental.setReservation(reservation);
            rental.setStatus(RentalStatus.active);
            rental.setStartDate(LocalDate.of(2026, 10, 1));
            rental.setContractEndDate(LocalDate.of(2026, 11, 1));
            rental.setMonthlyPrice(new BigDecimal("99999"));
            em.persist(rental);
        }

        CheckIn checkIn = new CheckIn();
        checkIn.setReservation(reservation);
        checkIn.setPerformedBy(staff);
        checkIn.setStatus(checkInStatus);
        em.persist(checkIn);
        em.flush();
        if (existingRental) {
            checkIn.setRental(rental);
            em.flush();
        }
        return new Fixture(reservation.getId(), new ActorPrincipal(customer.getId(), UUID.randomUUID(),
            Set.of(RoleCode.CUSTOMER), Set.of(), Map.of()), customer.getId(), facility.getId());
    }

    private User user(String kind) {
        User user = new User();
        user.setEmail(kind + "+" + UUID.randomUUID() + "@activation.invalid");
        user.setPasswordHash("test-only-not-a-login");
        user.setFullName("Ledger " + kind);
        user.setStatus(UserStatus.ACTIVE);
        em.persist(user);
        return user;
    }
}
