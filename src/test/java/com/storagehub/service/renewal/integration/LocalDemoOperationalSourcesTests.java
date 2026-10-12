package com.storagehub.service.renewal.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.storagehub.domain.model.Facility;
import com.storagehub.domain.model.Renewal;
import com.storagehub.domain.model.Rental;
import com.storagehub.service.overdue.LocalDemoRecoverySource;
import com.storagehub.service.overdue.OverdueSources;
import com.storagehub.service.renewal.operations.RenewalOperationSources;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.transaction.support.TransactionTemplate;

class LocalDemoOperationalSourcesTests {
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private final EntityManager em = mock(EntityManager.class);

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:ops-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("create table renewal_demo_recovery_cases(id binary(16) primary key, case_ref varchar(120) not null unique, rental_id binary(16) not null, facility_id binary(16) not null, status varchar(16) not null, reason varchar(2000) not null, received_at timestamp not null)");
        jdbc.execute("create table renewal_demo_signing_appointments(id binary(16) primary key, facility_id binary(16) not null, renewal_id binary(16) not null unique, appointment_start timestamp not null, appointment_end timestamp not null, status varchar(16) not null, created_at timestamp not null, updated_at timestamp not null, version bigint not null)");
    }

    @Test
    void recoveryStateUsesPersistedHandoffsAndRetryIsIdempotent() {
        UUID rentalId = UUID.randomUUID(), facilityId = UUID.randomUUID();
        Rental rental = mockRental(rentalId, facilityId);
        when(em.find(eq(Rental.class), eq(rentalId), eq(LockModeType.PESSIMISTIC_WRITE))).thenReturn(rental);
        LocalDemoRecoverySource source = new LocalDemoRecoverySource(jdbc, em);
        Instant now = Instant.parse("2026-10-11T02:00:00Z");

        tx.executeWithoutResult(status -> {
            assertThat(source.atomic()).isTrue();
            assertThat(source.state(rental, now).orElseThrow().recoveryBlocked()).isFalse();
            var accepted = source.receive(rental, "RENTAL_TERM:" + rentalId, "handoff", "key-1", now);
            assertThat(accepted.status()).isEqualTo("RECEIVED");
            assertThat(source.state(rental, now).orElseThrow().recoveryBlocked()).isTrue();
        });
        tx.executeWithoutResult(status -> {
            var retry = source.receive(rental, "RENTAL_TERM:" + rentalId, "handoff", "key-1", now);
            assertThat(retry.status()).isEqualTo("ALREADY_RECEIVED");
        });
        assertThat(jdbc.queryForObject("select count(*) from renewal_demo_recovery_cases", Integer.class)).isEqualTo(1);
    }

    @Test
    void recoveryRetryWithChangedPayloadIsRejected() {
        UUID rentalId = UUID.randomUUID(), facilityId = UUID.randomUUID();
        Rental rental = mockRental(rentalId, facilityId);
        when(em.find(eq(Rental.class), eq(rentalId), eq(LockModeType.PESSIMISTIC_WRITE))).thenReturn(rental);
        LocalDemoRecoverySource source = new LocalDemoRecoverySource(jdbc, em);
        Instant now = Instant.parse("2026-10-11T02:00:00Z");
        tx.executeWithoutResult(status -> source.receive(rental, "case-1", "reason-1", "key-1", now));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> source.receive(rental, "case-1", "reason-2", "key-1", now)))
            .hasMessageContaining("retry differs");
    }

    @Test
    void signingCalendarReservesAndReschedulesPersistentlyWithinOpenHours() {
        UUID facilityId = UUID.randomUUID(), renewalId = UUID.randomUUID();
        Facility facility = mock(Facility.class);
        when(facility.getId()).thenReturn(facilityId);
        when(facility.getStatus()).thenReturn(com.storagehub.domain.model.FacilityStatus.active);
        when(em.find(eq(Facility.class), eq(facilityId), eq(LockModeType.PESSIMISTIC_WRITE))).thenReturn(facility);
        Rental rental = mockRental(UUID.randomUUID(), facilityId);
        Renewal renewal = mock(Renewal.class);
        when(renewal.getId()).thenReturn(renewalId);
        when(renewal.getRental()).thenReturn(rental);
        LocalDemoSigningCalendarSource source = new LocalDemoSigningCalendarSource(jdbc, em);
        Instant day = java.time.LocalDate.of(2026, 10, 12).atTime(8, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        var first = new RenewalOperationSources.Slot(facilityId + ":" + day, day, day.plusSeconds(1800));
        var next = new RenewalOperationSources.Slot(facilityId + ":" + day.plusSeconds(1800), day.plusSeconds(1800), day.plusSeconds(3600));
        Instant now = day.minusSeconds(60);

        tx.executeWithoutResult(status -> source.reserve(renewal, first, now));
        tx.executeWithoutResult(status -> {
            assertThat(source.feasible(rental, day, day.plusSeconds(1800))).contains(false);
            assertThat(source.feasible(rental, day, day.plusSeconds(3600))).contains(true);
            source.reserve(renewal, next, now);
        });
        assertThat(jdbc.queryForObject("select count(*) from renewal_demo_signing_appointments", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select appointment_start from renewal_demo_signing_appointments where renewal_id=?", java.sql.Timestamp.class, bytes(renewalId)).toInstant()).isEqualTo(next.start());
        assertThat(source.feasible(rental, first.start(), first.end())).contains(true);
    }

    @Test
    void inactiveFacilityCannotReserveEvenWhenSlotWasPreviouslyAvailable() {
        UUID facilityId = UUID.randomUUID(), renewalId = UUID.randomUUID();
        Facility facility = mock(Facility.class);
        when(facility.getId()).thenReturn(facilityId);
        when(facility.getStatus()).thenReturn(com.storagehub.domain.model.FacilityStatus.inactive);
        when(em.find(eq(Facility.class), eq(facilityId), eq(LockModeType.PESSIMISTIC_WRITE))).thenReturn(facility);
        Renewal renewal = mock(Renewal.class);
        when(renewal.getId()).thenReturn(renewalId);
        Rental rental = mockRental(UUID.randomUUID(), facilityId);
        when(renewal.getRental()).thenReturn(rental);
        var source = new LocalDemoSigningCalendarSource(jdbc, em);
        Instant start = java.time.LocalDate.of(2026, 10, 12).atTime(8, 0)
            .atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        var slot = new RenewalOperationSources.Slot(facilityId + ":" + start, start, start.plusSeconds(1800));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> source.reserve(renewal, slot, start.minusSeconds(60))))
            .hasMessageContaining("facility is not active");
        assertThat(jdbc.queryForObject("select count(*) from renewal_demo_signing_appointments", Integer.class)).isZero();
    }

    @Test
    void additiveFlywayMigrationCreatesBothOperationalTables() {
        jdbc.execute("drop table renewal_demo_recovery_cases");
        jdbc.execute("drop table renewal_demo_signing_appointments");
        jdbc.execute("create table baseline_anchor(id integer primary key)");
        jdbc.execute("create table rentals(id binary(16) primary key)");
        jdbc.execute("create table facilities(id binary(16) primary key)");
        jdbc.execute("create table renewals(id binary(16) primary key)");
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/renewal-local-demo-migration")
            .baselineOnMigrate(true).baselineVersion("2026101104").target("2026101105").load();
        flyway.migrate();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name in ('RENEWAL_DEMO_RECOVERY_CASES','RENEWAL_DEMO_SIGNING_APPOINTMENTS')", Integer.class)).isEqualTo(2);
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("2026101105");
    }

    private Rental mockRental(UUID rentalId, UUID facilityId) {
        Facility facility = mock(Facility.class);
        when(facility.getId()).thenReturn(facilityId);
        Rental rental = mock(Rental.class);
        when(rental.getId()).thenReturn(rentalId);
        when(rental.getFacility()).thenReturn(facility);
        return rental;
    }

    private static byte[] bytes(UUID id) {
        var buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        return buffer.array();
    }
}
