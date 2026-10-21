package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;

import com.storagehub.config.DevelopMigrationHistoryLocations;
import com.storagehub.config.LedgerMigrationSafetyCallback;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs only against a disposable, explicitly named local schema. Never uses the application DB. */
@EnabledIfEnvironmentVariable(named="STORAGEHUB_SCHEMA_TEST_URL",matches=".+")
class BusinessRecordCodesMySqlTests {
    HikariDataSource datasource;
    JdbcTemplate jdbc;
    BusinessRecordCodes codes;
    TransactionTemplate tx;
    String adminUrl;
    String database;

    HikariDataSource connect(String url) {
        var result=new HikariDataSource();
        result.setJdbcUrl(url);
        result.setUsername(System.getenv("STORAGEHUB_SCHEMA_TEST_USERNAME"));
        result.setPassword(System.getenv("STORAGEHUB_SCHEMA_TEST_PASSWORD"));
        result.setMaximumPoolSize(8);
        return result;
    }

    @BeforeEach void setup() {
        adminUrl=System.getenv("STORAGEHUB_SCHEMA_TEST_URL");
        assertThat(adminUrl).matches("^jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/storagehub_schema_test_[a-z0-9_]+(?:\\?.*)?$");
        database="storagehub_schema_test_codes_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=connect(adminUrl)) {
            new JdbcTemplate(admin).execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        datasource=connect(adminUrl.replaceFirst("/storagehub_schema_test_[a-z0-9_]+","/"+database));
        jdbc=new JdbcTemplate(datasource);
        codes=new BusinessRecordCodes(jdbc);
        tx=new TransactionTemplate(new DataSourceTransactionManager(datasource));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @AfterEach void cleanup() {
        if(datasource!=null)datasource.close();
        if(database!=null&&database.matches("storagehub_schema_test_codes_[a-f0-9]{32}")) {
            try(var admin=connect(adminUrl)) {new JdbcTemplate(admin).execute("DROP DATABASE "+database);}
        }
    }

    void sourceTables() {
        jdbc.execute("CREATE TABLE renewals(id BINARY(16) PRIMARY KEY,created_at TIMESTAMP(6) NOT NULL)");
        jdbc.execute("CREATE TABLE support_tickets(id BINARY(16) PRIMARY KEY,created_at TIMESTAMP(6) NOT NULL)");
        jdbc.execute("CREATE TABLE overdue_follow_ups(case_ref VARCHAR(120),recorded_at TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE rentals(id BINARY(16) PRIMARY KEY,status VARCHAR(24),actual_returned_at TIMESTAMP(6),completed_at TIMESTAMP(6),start_date DATE,contract_end_date DATE)");
    }

    void migrateCodes() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2026102006__business_record_display_codes.sql")).execute(datasource);
    }

    @Test void canonicalMigrationChainAppliesAndValidatesOnEmptyMySql() {
        var flyway=DevelopMigrationHistoryLocations.configure(Flyway.configure().dataSource(datasource)
            .locations("classpath:db/migration").callbacks(new LedgerMigrationSafetyCallback())
            .baselineOnMigrate(false).cleanDisabled(true)).load();
        flyway.migrate();
        flyway.validate();
        flyway.migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='2026102006' AND success=1",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_record_codes",Integer.class)).isZero();
    }

    @Test void backfillsHistoricalCodesAndContinuesTheCorrectVietnamMonth() {
        sourceTables();
        String id=UUID.randomUUID().toString();
        String support=UUID.randomUUID().toString();
        Instant at=Instant.parse("2026-09-30T18:00:00Z");
        jdbc.update("INSERT INTO renewals VALUES(UUID_TO_BIN(?),?)",id,Timestamp.from(at));
        jdbc.update("INSERT INTO support_tickets VALUES(UUID_TO_BIN(?),?)",support,Timestamp.from(at));
        String ref="RENTAL_TERM:"+id;
        jdbc.update("INSERT INTO overdue_follow_ups VALUES(?,?),(?,?)",ref,Timestamp.from(at),ref,Timestamp.from(at.plusSeconds(60)));
        migrateCodes();
        assertThat(codes.find("GH:"+id)).isEqualTo("GH-2610-000001");
        assertThat(codes.find("HT:"+support)).isEqualTo("HT-2610-000001");
        assertThat(codes.find(ref)).isEqualTo("QH-2610-000001");
        assertThat(tx.<String>execute(s->codes.assign("GH","GH:"+UUID.randomUUID(),at))).isEqualTo("GH-2610-000002");
        assertThat(codes.resource("GH"," gh-2610-000001 ")).isEqualTo("GH:"+id);
        assertThat(tx.<String>execute(s->codes.assign("QH",ref,at.plusSeconds(2678400)))).isEqualTo("QH-2610-000001");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_record_codes WHERE resource_key=?",Integer.class,ref)).isEqualTo(1);
    }

    @Test void rolledBackCodesDoNotLeaveARegistryEntryOrConsumeTheCounter() {
        sourceTables();migrateCodes();
        Instant at=Instant.parse("2026-10-20T01:00:00Z");
        assertThatThrownBy(()->tx.execute(s->{codes.assign("HT","HT:rollback",at);throw new IllegalStateException("rollback-only test");})).isInstanceOf(IllegalStateException.class);
        assertThat(codes.find("HT:rollback")).isNull();
        assertThat(tx.<String>execute(s->codes.assign("HT","HT:committed",at))).isEqualTo("HT-2610-000001");
    }

    @Test void concurrentFirstAssignmentsForOneResourceReturnOneCode() throws Exception {
        sourceTables();migrateCodes();
        Instant at=Instant.parse("2026-10-20T01:00:00Z");
        var ready=new CountDownLatch(4);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var jobs=new ArrayList<Future<String>>();
            for(int i=0;i<4;i++)jobs.add(pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout");return tx.execute(s->codes.assign("GH","GH:concurrent",at));}));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            var results=new HashSet<String>();
            for(var job:jobs)results.add(job.get(20,TimeUnit.SECONDS));
            assertThat(results).containsExactly("GH-2610-000001");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM business_record_codes",Integer.class)).isEqualTo(1);
        }
    }
}
