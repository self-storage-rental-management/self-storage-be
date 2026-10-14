package com.storagehub.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class RenewalCalendarStoreTests {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private RenewalCalendarStore store;
    private java.sql.Connection schemaConnection;
    private final UUID staff=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
    private final UUID facilityA=UUID.randomUUID(),facilityB=UUID.randomUUID(),quote=UUID.randomUUID();
    private final Instant start=Instant.parse("2026-10-14T01:00:00Z"),end=start.plusSeconds(1800);

    @BeforeEach void setUp() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:staff-calendar-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        jdbc.execute("create table users(id binary(16) primary key)");
        jdbc.execute("create table facilities(id binary(16) primary key,status varchar(16))");
        jdbc.execute("create table renewals(id binary(16) primary key)");
        jdbc.execute("create table renewal_operation_states(id binary(16) primary key,phase varchar(24),appointment_start timestamp(6),appointment_end timestamp(6))");
        // H2 2.4 CHECK optimization retains its DDL session; keep that session alive while exercising inserts.
        schemaConnection=ds.getConnection();
        new ResourceDatabasePopulator(new ClassPathResource("db/migration-history/legacy-calendar-1301/V2026101301__create_staff_renewal_signing_calendar.sql")).populate(schemaConnection);
        jdbc.update("insert into users values(?)",bytes(staff));
        for(UUID id:new UUID[]{facilityA,facilityB})jdbc.update("insert into facilities values(?,'active')",bytes(id));
        for(UUID id:new UUID[]{a,b})jdbc.update("insert into renewals values(?)",bytes(id));
        jdbc.update("insert into renewal_calendar_staff_locks values(?)",bytes(staff));
        store=new RenewalCalendarStore(jdbc);
    }
    @AfterEach void closeSchemaConnection() throws Exception {if(schemaConnection!=null)schemaConnection.close();}
    @Test void competingBookingsAtDifferentFacilitiesCannotDoubleBookTheSameStaff() throws Exception {
        var barrier=new CyclicBarrier(2);var accepted=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->attempt(a,facilityA,barrier,accepted));
            var second=pool.submit(()->attempt(b,facilityB,barrier,accepted));
            first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
            assertThat(accepted.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from renewal_signing_reservations",Integer.class)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    private void attempt(UUID id,UUID facility,CyclicBarrier barrier,AtomicInteger accepted) {
        tx.executeWithoutResult(status->{
            jdbc.queryForObject("select count(*) from renewal_signing_reservations",Integer.class); // Read before the competing commit; capacity is re-read under the Staff mutex.
            try {barrier.await(5,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}
            store.lockStaff(staff);
            if(store.busy(staff,start,end,id).isEmpty()) {
                store.reserve(id,facility,staff,quote,start,end,start.minusSeconds(60));accepted.incrementAndGet();
            }
        });
    }
    @Test void failedRescheduleRollsBackTheOriginalReservation() {
        tx.executeWithoutResult(s->{store.lockStaff(staff);store.reserve(a,facilityA,staff,quote,start,end,start.minusSeconds(60));});
        assertThatThrownBy(()->tx.executeWithoutResult(s->{
            store.lockStaff(staff);store.reserve(a,facilityA,staff,quote,end,end.plusSeconds(1800),start);
            throw new IllegalStateException("workflow failed");
        })).hasMessage("workflow failed");
        assertThat(store.current(a,false).orElseThrow().start()).isEqualTo(start);
        tx.executeWithoutResult(s->{store.lockStaff(staff);store.release(a,end);});
        assertThat(store.busy(staff,start,end,b)).isEmpty();
    }
    @Test void adjacentHalfHourAppointmentsDoNotConflict() {
        tx.executeWithoutResult(s->{store.lockStaff(staff);store.reserve(a,facilityA,staff,quote,start,end,start.minusSeconds(60));});
        assertThat(store.busy(staff,end,end.plusSeconds(1800),b)).isEmpty();
        assertThat(store.busy(staff,start.plusSeconds(1),end,b)).hasSize(1);
    }
    @Test void historicalAppointmentsCannotBeAssumedCoveredByAnEmptyNewCalendar() {
        jdbc.update("insert into renewal_operation_states values(?,'SIGNING',?,?)",bytes(a),java.sql.Timestamp.from(start),java.sql.Timestamp.from(end));
        assertThat(store.legacyAppointmentsUncovered(start.minusSeconds(60))).isTrue();
        tx.executeWithoutResult(s->{store.lockStaff(staff);store.reserve(a,facilityA,staff,quote,start,end,start.minusSeconds(60));});
        assertThat(store.legacyAppointmentsUncovered(start.minusSeconds(60))).isFalse();
    }
    @Test void calendarWritesRequireTheCallerTransaction() {
        assertThatThrownBy(()->store.lockStaff(staff)).hasMessageContaining("writable transaction");
    }
    @Test void repeatableReadWriterIsRejectedRatherThanUsingAnOlderCapacitySnapshot() {
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(()->tx.executeWithoutResult(s->store.lockStaff(staff))).hasMessageContaining("READ_COMMITTED");
    }
    private static byte[] bytes(UUID id){return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();}
}
