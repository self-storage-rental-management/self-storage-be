package com.storagehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

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
        for (String table : java.util.List.of("return_cases", "maintenance_tasks", "refund_requests")) {
            jdbc.execute("CREATE TABLE " + table + "(id BINARY(16) PRIMARY KEY,created_at TIMESTAMP(6) NOT NULL)");
        }
        jdbc.execute("CREATE TABLE renewals(id BINARY(16) PRIMARY KEY,created_at TIMESTAMP(6) NOT NULL)");
        jdbc.execute("CREATE TABLE support_tickets(id BINARY(16) PRIMARY KEY,created_at TIMESTAMP(6) NOT NULL)");
        jdbc.execute("CREATE TABLE overdue_follow_ups(case_ref VARCHAR(120),recorded_at TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE rentals(id BINARY(16) PRIMARY KEY,created_at TIMESTAMP(6) NOT NULL,status VARCHAR(24),actual_returned_at TIMESTAMP(6),completed_at TIMESTAMP(6),start_date DATE,contract_end_date DATE)");
    }

    void migrateCodes() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2026102006__business_record_display_codes.sql")).execute(datasource);
    }

    void migrateHistoricalRentalCodes() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2026112204__backfill_rental_business_codes.sql")).execute(datasource);
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

    @Test void receiptMigrationPreservesExistingCodesAndConcurrentIssuanceIsIdempotent() throws Exception {
        jdbc.execute("CREATE TABLE payments(id INT PRIMARY KEY, receipt_code VARCHAR(17) NULL)");
        jdbc.update("INSERT INTO payments VALUES (1, 'legacy')");
        var flyway = Flyway.configure().dataSource(datasource)
            .locations("classpath:db/migration-history/receipt-checked-2403").baselineOnMigrate(true).load();
        flyway.migrate();
        flyway.validate();
        var receipts = new MoneyReceiptCodes(jdbc);
        var source = UUID.randomUUID();
        var start = new CyclicBarrier(2);
        Callable<String> issue = () -> tx.execute(status -> {
            try {
                start.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return receipts.issue("PAYMENT", source, false);
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(issue);
            var second = pool.submit(issue);
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM money_receipt_codes", Integer.class)).isOne();
        assertThat(receipts.find("PAYMENT", source)).startsWith("PT-");
        assertThat(jdbc.queryForObject("SELECT receipt_code FROM payments WHERE id=1", String.class)).isEqualTo("legacy");
    }

    /**
     * The recovery module is optional, so its receipt table is absent on a base install.
     * Historical receipt reconciliation must still run instead of erroring every 30 seconds.
     */
    @Test void historicalReceiptsRunWhenOptionalRecoveryModuleIsNotInstalled() {
        receiptSchema();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables"
            + " WHERE table_schema=database() AND table_name='recovery_settlement_receipts'", Integer.class)).isZero();

        var coordinator = coordinator();

        // Would throw BadSqlGrammarException before the optional table was probed.
        tx.executeWithoutResult(status -> coordinator.registerHistoricalReceipts());

        var returned = UUID.randomUUID();
        insertSettledReturn(returned, "TX-1");
        jdbc.update("INSERT INTO return_settlement_receipts VALUES(?, 'REFUND', 50000, 'CASH', 'TX-1', ?, ?, 1)",
            returned.toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());

        tx.executeWithoutResult(status -> coordinator.registerHistoricalReceipts());

        assertThat(new MoneyReceiptCodes(jdbc).find("RETURN", returned)).isNotNull();
    }

    /** When the recovery module is installed, receipts recorded only there must still be found. */
    @Test void historicalReceiptsUseRecoveryReceiptsWhenModuleIsInstalled() {
        receiptSchema();
        jdbc.execute("CREATE TABLE recovery_settlement_receipts(task_id BINARY(16) PRIMARY KEY,"
            + "return_case_id BINARY(16) NOT NULL,direction VARCHAR(16) NOT NULL,amount DECIMAL(14,2) NOT NULL,"
            + "payment_method VARCHAR(24) NOT NULL,transaction_reference VARCHAR(64) NOT NULL,"
            + "receipt_file_id BINARY(16) NOT NULL,recorded_by BINARY(16) NOT NULL,recorded_at TIMESTAMP(6) NOT NULL)"
            + " ENGINE=InnoDB");

        var coordinator = coordinator();
        var returned = UUID.randomUUID();
        insertSettledReturn(returned, "TX-2");
        // Only the recovery table has the receipt, so the code must come from that branch.
        jdbc.update("INSERT INTO recovery_settlement_receipts VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'REFUND',50000,"
                + "'CASH','TX-2',UUID_TO_BIN(?),UUID_TO_BIN(?),?)",
            UUID.randomUUID().toString(), returned.toString(), UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), Timestamp.from(Instant.parse("2026-11-02T00:00:00Z")));

        tx.executeWithoutResult(status -> coordinator.registerHistoricalReceipts());

        assertThat(new MoneyReceiptCodes(jdbc).find("RETURN", returned)).isNotNull();
    }

    void receiptSchema() {
        sourceTables();
        migrateCodes();
        jdbc.execute("CREATE TABLE payments(id BINARY(16) PRIMARY KEY,created_at TIMESTAMP(6) NOT NULL,status VARCHAR(16),receipt_code VARCHAR(17))");
        jdbc.execute("CREATE TABLE money_receipt_codes(source_type VARCHAR(16) NOT NULL,source_id BINARY(16) NOT NULL,"
            + "receipt_code CHAR(17) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
            + "PRIMARY KEY(source_type,source_id),UNIQUE KEY uk_money_receipt_code(receipt_code)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE return_settlement_receipts(return_id VARCHAR(36) PRIMARY KEY,direction VARCHAR(16),amount DECIMAL(20,0),payment_method VARCHAR(24),transaction_reference VARCHAR(120),receipt_file_id VARCHAR(36),recorded_by VARCHAR(36),recorded_at_ms BIGINT)");
        jdbc.execute("ALTER TABLE return_cases ADD COLUMN status VARCHAR(24), ADD COLUMN settlement_paid_at TIMESTAMP(6),"
            + " ADD COLUMN settlement_payment_id VARCHAR(64), ADD COLUMN net_refund_amount DECIMAL(20,0),"
            + " ADD COLUMN amount_due_from_customer DECIMAL(20,0)");
    }

    void insertSettledReturn(UUID returned, String settlementPaymentId) {
        jdbc.update("INSERT INTO return_cases(id,created_at,status,settlement_paid_at,settlement_payment_id,"
                + "net_refund_amount,amount_due_from_customer) VALUES(UUID_TO_BIN(?),?,'completed',?,?,50000,0)",
            returned.toString(), Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")),
            Timestamp.from(Instant.parse("2026-11-02T00:00:00Z")), settlementPaymentId);
    }

    BusinessRecordCodeCoordinator coordinator() {
        var em = mock(EntityManager.class);
        var coordinator = new BusinessRecordCodeCoordinator(jdbc, em, codes, null, null, null);
        try {
            var field = BusinessRecordCodeCoordinator.class.getDeclaredField("receiptCodes");
            field.setAccessible(true);
            field.set(coordinator, new MoneyReceiptCodes(jdbc));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return coordinator;
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
        assertThat(tx.<String>execute(s->codes.assign("GH","GH:"+UUID.randomUUID(),at))).matches("SH-[0-9]{6}");
        assertThat(codes.resource("GH"," gh-2610-000001 ")).isEqualTo("GH:"+id);
        assertThat(tx.<String>execute(s->codes.assign("QH",ref,at.plusSeconds(2678400)))).isEqualTo("QH-2610-000001");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_record_codes WHERE resource_key=?",Integer.class,ref)).isEqualTo(1);
    }

    @Test void concurrentRecordsKeepDistinctCodesAndResolveOnlyInTheirOwnType() throws Exception {
        sourceTables();
        migrateCodes();
        var records = Map.of("TH:one", "TH", "GH:one", "GH", "GH:two", "GH", "HT:one", "HT", "RENTAL_TERM:one", "QH");
        var start = new CyclicBarrier(records.size());
        var assigned = new HashSet<String>();
        try (var pool = Executors.newFixedThreadPool(records.size())) {
            var jobs = new HashMap<String, Future<String>>();
            for (var entry : records.entrySet()) {
                jobs.put(entry.getKey(), pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return tx.execute(s -> codes.assign(entry.getValue(), entry.getKey(), Instant.now()));
                }));
            }
            for (var entry : records.entrySet()) {
                String key = entry.getKey(), type = entry.getValue();
                String code = jobs.get(key).get(15, TimeUnit.SECONDS);
                assertThat(code).matches("SH-[1-9][0-9]{5}");
                assertThat(assigned.add(code)).isTrue();
                assertThat(codes.find(key)).isEqualTo(code);
                assertThat(tx.<String>execute(s -> codes.assign(type, key, Instant.now()))).isEqualTo(code);
                assertThat(codes.resource(type, " " + code.toLowerCase(Locale.ROOT) + " ")).isEqualTo(key);
                assertThat(codes.resource(type.equals("GH") ? "HT" : "GH", code)).isNull();
            }
        }
    }

    @Test void migrationSynchronouslyBackfillsMoreThanOneCoordinatorBatchOfHistoricalRentals() {
        sourceTables();
        migrateCodes();
        var created = Timestamp.from(Instant.parse("2026-11-01T00:00:00Z"));
        jdbc.update("INSERT INTO business_record_codes(resource_key,type_code,period,display_code,recorded_at) VALUES('HT:occupied','HT','2611','SH-100000',?)", created);
        var rentalIds = new ArrayList<String>();
        for (int index = 0; index < 137; index++) {
            String rentalId = UUID.randomUUID().toString();
            rentalIds.add(rentalId);
            jdbc.update("INSERT INTO rentals(id,created_at,status) VALUES(UUID_TO_BIN(?),?,'active')", rentalId, created);
        }

        migrateHistoricalRentalCodes();

        var keys = rentalIds.stream().map(id -> "TH:" + id).toList();
        var assigned = codes.findAll(keys);
        assertThat(assigned).hasSize(137).containsOnlyKeys(keys);
        assertThat(assigned.values()).doesNotHaveDuplicates()
            .allMatch(code -> code.matches("SH-[1-9][0-9]{5}"));
        assertThat(assigned.values()).doesNotContain("SH-100000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void existingActiveAndCompletedRentalsKeepTheirCodesAcrossRegistrationRuns() {
        sourceTables();
        migrateCodes();
        String active = UUID.randomUUID().toString();
        String completed = UUID.randomUUID().toString();
        var created = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
        jdbc.update("INSERT INTO rentals(id, created_at, status) VALUES(UUID_TO_BIN(?), ?, 'active'), (UUID_TO_BIN(?), ?, 'completed')",
            active, created, completed, created);

        var em = mock(EntityManager.class);
        TypedQuery<UUID> overdueRentals = mock(TypedQuery.class, RETURNS_SELF);
        when(em.createQuery(anyString(), eq(UUID.class))).thenReturn(overdueRentals);
        when(overdueRentals.getResultList()).thenReturn(List.of());
        var coordinator = new BusinessRecordCodeCoordinator(jdbc, em, codes, null, null, null);
        tx.executeWithoutResult(status -> coordinator.register());
        var keys = List.of("TH:" + active, "TH:" + completed);
        var labels = codes.findAll(keys);
        assertThat(labels).containsOnlyKeys(keys);
        assertThat(labels.values()).doesNotHaveDuplicates().allMatch(code -> code.matches("SH-[1-9][0-9]{5}"));
        tx.executeWithoutResult(status -> coordinator.register());
        assertThat(codes.findAll(keys)).isEqualTo(labels);
        assertThat(codes.findAll(List.of())).isEmpty();
    }

    @Test void rolledBackCodesDoNotLeaveARegistryEntryOrConsumeTheCounter() {
        sourceTables();migrateCodes();
        Instant at=Instant.parse("2026-10-20T01:00:00Z");
        assertThatThrownBy(()->tx.execute(s->{codes.assign("HT","HT:rollback",at);throw new IllegalStateException("rollback-only test");})).isInstanceOf(IllegalStateException.class);
        assertThat(codes.find("HT:rollback")).isNull();
        assertThat(tx.<String>execute(s->codes.assign("HT","HT:committed",at))).matches("SH-[0-9]{6}");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {
        TransactionDefinition.ISOLATION_READ_COMMITTED, TransactionDefinition.ISOLATION_REPEATABLE_READ
    })
    void concurrentFirstAssignmentsForOneResourceReturnOneCode(int isolation) throws Exception {
        tx.setIsolationLevel(isolation);
        sourceTables();migrateCodes();
        Instant at=Instant.parse("2026-10-20T01:00:00Z");
        var ready=new CountDownLatch(4);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var jobs=new ArrayList<Future<String>>();
            for (int i = 0; i < 4; i++) {
                jobs.add(pool.submit(() -> tx.execute(s -> {
                    assertThat(codes.find("GH:concurrent")).isNull();
                    ready.countDown();
                    try {
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return codes.assign("GH", "GH:concurrent", at);
                })));
            }
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            var results=new HashSet<String>();
            for(var job:jobs)results.add(job.get(20,TimeUnit.SECONDS));
            assertThat(results).hasSize(1);
            assertThat(results.iterator().next()).matches("SH-[0-9]{6}");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM business_record_codes",Integer.class)).isEqualTo(1);
        }
    }
}
