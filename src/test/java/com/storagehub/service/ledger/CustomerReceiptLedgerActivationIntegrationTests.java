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
import org.springframework.test.context.transaction.TestTransaction;

/** Real JPA/JDBC persistence for the existing Customer receipt activation flow, isolated in H2. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=${STORAGEHUB_LEDGER_E2E_DB_URL:jdbc:h2:mem:ledger-activation;MODE=MySQL;DB_CLOSE_DELAY=-1}",
    "spring.datasource.driver-class-name=${STORAGEHUB_LEDGER_E2E_DRIVER:org.h2.Driver}",
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
    @Autowired LedgerCoverageInventory coverageInventory;
    @Autowired com.storagehub.service.ReturnSettlementService returns;
    @Autowired com.storagehub.domain.repo.ReturnCaseRepository returnCases;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("activationAuditLogService") AuditLogService activationAudit;
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
                assertThat(database).startsWith("storagehub_v5_test_");
                var latest = jdbc.queryForMap(
                    "SELECT version, description, success FROM storagehub_local.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1");
                assertThat(Boolean.parseBoolean(latest.get("success").toString())).isTrue();
                List<String> tables = jdbc.queryForList(
                    "SELECT LOWER(table_name) FROM information_schema.tables WHERE table_schema=DATABASE() " +
                        "AND table_name IN ('rental_ledger_accounts','rental_ledger_obligations','rental_ledger_receipts','rental_ledger_allocations','rental_ledger_refunds','rental_ledger_commands')",
                    String.class);
                assertThat(tables).containsExactlyInAnyOrder(
                    "rental_ledger_accounts", "rental_ledger_obligations", "rental_ledger_receipts",
                    "rental_ledger_allocations", "rental_ledger_refunds", "rental_ledger_commands");
                System.out.println("Ledger Slice 1 DB preflight PASS: database=" + database + "; mysql=" +
                    serverVersion + "; source schema flyway=" + latest.get("version") + "; six isolated ledger tables present; no Flyway history fabricated");
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
        var persistedRental=em.find(Rental.class,rentalId);
        var persistedSnapshot=em.createQuery("select s from ReservationPricingSnapshot s where s.reservation.id=:id",ReservationPricingSnapshot.class).setParameter("id",fixture.reservationId()).getSingleResult();
        var specs=FinancialContractSpecifications.initial(persistedRental,persistedSnapshot);
        assertThat(specs).extracting(FinancialContractSpecifications.Specification::kind).containsExactly(LedgerStore.Kind.RENT,LedgerStore.Kind.SECURITY_DEPOSIT);
        assertThat(specs.getFirst().sourceReference()).contains(persistedSnapshot.getId().toString());
        assertThat(account.obligations()).isEmpty();
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
    void incompleteHandoverAndCancelledOrExpiredReservationDoNotCreateRentalOrLedgerAccount() {
        Fixture pending = fixture(ReservationStatus.AWAITING_CUSTOMER_RECEIPT, CheckInStatus.scheduled, false);
        assertThatThrownBy(() -> service.confirmReceipt(pending.actor(), pending.reservationId()))
            .hasMessageContaining("handover is not completed");

        Fixture cancelled = fixture(ReservationStatus.CANCELLED, CheckInStatus.completed, false);
        assertThatThrownBy(() -> service.confirmReceipt(cancelled.actor(), cancelled.reservationId()))
            .hasMessageContaining("not waiting for customer receipt");

        Fixture expired = fixture(ReservationStatus.EXPIRED, CheckInStatus.completed, false);
        assertThatThrownBy(() -> service.confirmReceipt(expired.actor(), expired.reservationId()))
            .hasMessageContaining("not waiting for customer receipt");

        assertThat(rentals.findByReservation_Id(pending.reservationId())).isEmpty();
        assertThat(rentals.findByReservation_Id(cancelled.reservationId())).isEmpty();
        assertThat(rentals.findByReservation_Id(expired.reservationId())).isEmpty();
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

    @Test @Order(5)
    void committedActivationIsVisibleToIndependentConnectionAndReplay() throws Exception {
        var f=fixture(ReservationStatus.AWAITING_CUSTOMER_RECEIPT,CheckInStatus.completed,false);
        service.confirmReceipt(f.actor(),f.reservationId());
        UUID id=rentals.findByReservation_Id(f.reservationId()).orElseThrow().getId();
        TestTransaction.flagForCommit();TestTransaction.end();
        try(var connection=jdbc.getDataSource().getConnection();var p=connection.prepareStatement("select customer_id,facility_id,revision from rental_ledger_accounts where rental_id=?")) {
            p.setString(1,id.toString());try(var result=p.executeQuery()){assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo(f.customerId().toString());assertThat(result.getString(2)).isEqualTo(f.facilityId().toString());assertThat(result.getLong(3)).isZero();assertThat(result.next()).isFalse();}
        }
        service.confirmReceipt(f.actor(),f.reservationId());
        assertThat(ledger.read(id).orElseThrow().obligations()).isEmpty();
        TestTransaction.start();
    }
    @Test @Order(6)
    void failureAfterLedgerOpeningRollsBackRentalUnitCheckinAndReservation() {
        var f=fixture(ReservationStatus.AWAITING_CUSTOMER_RECEIPT,CheckInStatus.completed,false);
        TestTransaction.flagForCommit();TestTransaction.end();
        org.mockito.Mockito.doThrow(new IllegalStateException("Injected audit failure after ledger opening")).when(activationAudit).recordMutation(
            org.mockito.ArgumentMatchers.any(User.class),org.mockito.ArgumentMatchers.eq("CUSTOMER_RECEIPT_CONFIRMED"),org.mockito.ArgumentMatchers.eq("Reservation"),
            org.mockito.ArgumentMatchers.eq(f.reservationId()),org.mockito.ArgumentMatchers.eq(f.facilityId()),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        long before=jdbc.queryForObject("select count(*) from rental_ledger_accounts",Long.class);
        try {assertThatThrownBy(()->service.confirmReceipt(f.actor(),f.reservationId())).hasMessageContaining("Injected audit failure");}
        finally {org.mockito.Mockito.reset(activationAudit);}
        TestTransaction.start();em.clear();
        assertThat(rentals.findByReservation_Id(f.reservationId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from rental_ledger_accounts",Long.class)).isEqualTo(before);
        var res=em.find(Reservation.class,f.reservationId());assertThat(res.getStatus()).isEqualTo(ReservationStatus.AWAITING_CUSTOMER_RECEIPT);
        assertThat(res.getAssignedUnit().getStatus()).isEqualTo(StorageUnitStatus.assigned);
        var checkin=em.createQuery("select c from CheckIn c where c.reservation.id=:id",CheckIn.class).setParameter("id",f.reservationId()).getSingleResult();assertThat(checkin.getRental()).isNull();
    }
    @Test @Order(7)
    void immutableAcceptedRevisionMapsOnlyItsOwnRenewalPurposesAndRejectsHashTampering() throws Exception {
        var r=em.createQuery("select r from Rental r",Rental.class).setMaxResults(1).getSingleResult();
        var start=r.getContractEndDate().plusDays(1);var end=start.plusMonths(3);
        var t=new com.storagehub.api.renewal.RenewalQuoteResponse.Terms(r.getContractEndDate(),start,end,end.minusDays(1),r.getStorageUnit().getUnitType().getId(),"TEST-3M",UUID.randomUUID(),"test-v1",3,new BigDecimal("100"),BigDecimal.ZERO,new BigDecimal("300"),BigDecimal.ZERO,new BigDecimal("300"),new BigDecimal("60"),new BigDecimal("240"),"VND","test-policy","v1",r.getStorageUnit().getId(),r.getFacility().getId());
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();var json=mapper.writeValueAsString(t);
        var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var now=Instant.now();var q=new com.storagehub.service.renewal.persistence.RenewalQuote(r,r.getCustomer(),json,hash,now,now.plusSeconds(600));em.persist(q);
        var n=new Renewal();n.setRental(r);n.setRequestedBy(r.getCustomer());n.setAmount(t.totalAfterDiscount());n.setNewEndDate(t.newEndDate());em.persist(n);
        var revision=new com.storagehub.service.renewal.persistence.RenewalAcceptedRevision(n,q,1,now,"TEST_ONLY immutable source validation");em.persist(revision);em.flush();var id=revision.getId();em.clear();
        var stored=em.find(com.storagehub.service.renewal.persistence.RenewalAcceptedRevision.class,id);
        var specs=FinancialContractSpecifications.accepted(stored,t);assertThat(specs).extracting(FinancialContractSpecifications.Specification::kind).containsExactly(LedgerStore.Kind.RENEWAL_DEPOSIT,LedgerStore.Kind.RENEWAL_REMAINDER);
        assertThat(specs.getFirst().sourceReference()).contains(id.toString(),q.getId().toString());assertThat(ledger.read(r.getId()).orElseThrow().obligations()).isEmpty();
        em.createNativeQuery("update renewal_quotes set terms_hash=:hash where id=:id").setParameter("hash","0".repeat(64)).setParameter("id",q.getId()).executeUpdate();em.clear();
        var changed=em.find(com.storagehub.service.renewal.persistence.RenewalAcceptedRevision.class,id);
        assertThatThrownBy(()->FinancialContractSpecifications.accepted(changed,t)).hasMessageContaining("hash mismatch");
    }

    @Test @Order(8)
    void persistedInventoryExplainsEmptyAccountWithoutCertifyingZeroDebt() {
        var f=fixture(ReservationStatus.AWAITING_CUSTOMER_RECEIPT,CheckInStatus.completed,false);
        service.confirmReceipt(f.actor(),f.reservationId());em.flush();em.clear();
        var rental=rentals.findByReservation_Id(f.reservationId()).orElseThrow();
        var inventory=coverageInventory.inspect(rental,ledger.read(rental.getId()));
        assertThat(inventory.completeness()).isEqualTo("PARTIAL");
        assertThat(inventory.gaps()).contains("INITIAL_OBLIGATION_UNPOSTED_OR_INVALID:RENT",
            "INITIAL_OBLIGATION_UNPOSTED_OR_INVALID:SECURITY_DEPOSIT","BOOKING_PAYMENT_EVIDENCE_MISSING");
        assertThat(inventory.gaps()).doesNotContain("BOOKING_SNAPSHOT_INVALID");
        assertThat(inventory.consistentThroughApproval()).isFalse();
    }

    @Test @Order(10)
    void returnSearchSupportsOptionalFiltersAndStrictFacilityScopes() {
        var f = fixture(ReservationStatus.AWAITING_CUSTOMER_RECEIPT, CheckInStatus.completed, false);
        service.confirmReceipt(f.actor(), f.reservationId());
        var rental = rentals.findByReservation_Id(f.reservationId()).orElseThrow();
        // This test exercises repository filters, not the separate payment-evidence gate.
        var requested = new ReturnCase();
        requested.setRental(rental);requested.setFacility(rental.getFacility());requested.setStorageUnit(rental.getStorageUnit());
        requested.setCustomer(rental.getCustomer());requested.setRequestedBy(rental.getCustomer());
        requested.setRequestedAt(Instant.now());requested.setScheduledDate(LocalDate.now().plusDays(1));
        requested.setCustomerNotes("TEST search");em.persist(requested);
        em.flush();
        var page = org.springframework.data.domain.PageRequest.of(0, 20);
        assertThat(returnCases.searchReturnCases(null, null, List.of(f.facilityId()), null, page).getContent())
            .extracting(ReturnCase::getId).containsExactly(requested.getId());
        assertThat(returnCases.searchReturnCases(f.facilityId(), ReturnCaseStatus.requested, null, "Ledger customer", page).getContent())
            .extracting(ReturnCase::getId).containsExactly(requested.getId());
        assertThat(returnCases.searchReturnCases(null, null, List.of(), null, page)).isEmpty();
        assertThat(returnCases.searchReturnCases(null, null, List.of(UUID.randomUUID()), null, page)).isEmpty();
        assertThat(returnCases.searchReturnCases(f.facilityId(), ReturnCaseStatus.completed, null, null, page)).isEmpty();
        assertThat(returnCases.searchReturnCases(f.facilityId(), null, null, "no matching search phrase", page)).isEmpty();
        assertThat(returnCases.searchReturnCases(f.facilityId(), null, null, null,
            org.springframework.data.domain.PageRequest.of(1, 1))).isEmpty();
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
