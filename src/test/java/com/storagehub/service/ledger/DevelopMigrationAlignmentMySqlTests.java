package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.config.LedgerMigrationSafetyCallback;
import com.zaxxer.hikari.HikariDataSource;
import db.migration.V2026101102__align_vnpay_and_customer_checkin_schema;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;

/** Real MySQL, new isolated databases only; no shared data/history repair. */
@EnabledIfEnvironmentVariable(named="STORAGEHUB_SCHEMA_TEST_URL",matches=".+")
class DevelopMigrationAlignmentMySqlTests {
    HikariDataSource datasource; JdbcTemplate jdbc;
    @BeforeEach void createFreshIsolatedDatabase() {
        String url=System.getenv("STORAGEHUB_SCHEMA_TEST_URL");
        assertThat(url).matches("^jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/storagehub_schema_test_[a-z0-9_]+(?:\\?.*)?$");
        String database="storagehub_schema_test_alignment_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        try(var admin=new HikariDataSource()) {
            admin.setJdbcUrl(url);admin.setUsername(System.getenv("STORAGEHUB_SCHEMA_TEST_USERNAME"));admin.setPassword(System.getenv("STORAGEHUB_SCHEMA_TEST_PASSWORD"));
            new JdbcTemplate(admin).execute("CREATE DATABASE "+database);
        }
        datasource=new HikariDataSource();datasource.setJdbcUrl(url.replaceFirst("/storagehub_schema_test_[a-z0-9_]+","/"+database));
        datasource.setUsername(System.getenv("STORAGEHUB_SCHEMA_TEST_USERNAME"));datasource.setPassword(System.getenv("STORAGEHUB_SCHEMA_TEST_PASSWORD"));
        jdbc=new JdbcTemplate(datasource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class)).isZero();
        System.out.println("Migration alignment isolated DB: "+database);
    }
    @AfterEach void close(){if(datasource!=null)datasource.close();}
    Flyway common(){return com.storagehub.config.DevelopMigrationHistoryLocations.configure(Flyway.configure().dataSource(datasource).locations("classpath:db/migration")
        .callbacks(new LedgerMigrationSafetyCallback()).baselineOnMigrate(false).cleanDisabled(true).outOfOrder(false)).load();}
    void minimalTables(){jdbc.execute("CREATE TABLE payments(id BINARY(16) PRIMARY KEY,amount DECIMAL(14,2) NOT NULL,status VARCHAR(32) NOT NULL)");jdbc.execute("CREATE TABLE reservations(id BINARY(16) PRIMARY KEY,status VARCHAR(32) NOT NULL)");}
    void align() throws Exception {
        try(var connection=datasource.getConnection()) {
            var context=mock(Context.class);when(context.getConnection()).thenReturn(connection);
            new V2026101102__align_vnpay_and_customer_checkin_schema().migrate(context);
        }
    }
    long columnCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND ((table_name='payments' AND column_name IN ('gateway_provider','gateway_transaction_no','gateway_bank_code','gateway_card_type','gateway_response_code','gateway_transaction_status','gateway_pay_date','last_reconciled_at','refunded_amount')) OR (table_name='reservations' AND column_name='appointment_at'))",Long.class);}
    @Test void freshCanonicalChainHasUniqueVersionsAndCorrectColumns() {
        var flyway=common();flyway.migrate();flyway.validate();
        assertThat(columnCount()).isEqualTo(10);
        assertThat(flyway.info().all()).extracting(info->info.getVersion().toString()).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Long.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('2026100910','2026101101')",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rental_ledger_receipts",Long.class)).isZero();
    }
    @Test void installedLedgerUpgradesWithoutChangingOldHistoryOrExistingUser() {
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration","classpath:db/migration-history/legacy-0909","classpath:db/migration-history/legacy-1001")
            .callbacks(new LedgerMigrationSafetyCallback()).target("2026101001").outOfOrder(false).cleanDisabled(true).load().migrate();
        var oldHistory=jdbc.queryForList("SELECT version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        String id=UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO users(id,created_at,updated_at,email,password_hash,full_name,status,must_change_password) VALUES(UNHEX(?),NOW(6),NOW(6),?,'TEST-ONLY','PRESERVE USER','ACTIVE',false)",id,id+"@isolated.invalid");
        var user=jdbc.queryForMap("SELECT * FROM users WHERE id=UNHEX(?)",id);
        var flyway=common();flyway.migrate();flyway.validate();flyway.migrate();
        assertThat(jdbc.queryForList("SELECT version,script,checksum,success FROM flyway_schema_history WHERE version NOT IN ('2026101102','2026101103') ORDER BY installed_rank")).isEqualTo(oldHistory);
        assertThat(jdbc.queryForMap("SELECT * FROM users WHERE id=UNHEX(?)",id)).usingRecursiveComparison().isEqualTo(user);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='2026101102'",Long.class)).isEqualTo(1);
    }
    @Test void exactExistingColumnsAndValuesArePreservedAndReplayIsNoop() throws Exception {
        minimalTables();align();
        String id=UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO payments(id,amount,status,gateway_provider,gateway_transaction_no,refunded_amount,last_reconciled_at) VALUES(UNHEX(?),100.00,'PAID','VNPAY','TEST-TRANSACTION',25.00,'2026-10-11 01:02:03.123456')",id);
        jdbc.update("INSERT INTO reservations(id,status,appointment_at) VALUES(UNHEX(?),'CONFIRMED','2026-10-12 02:03:04.123456')",id);
        var payment=jdbc.queryForMap("SELECT * FROM payments WHERE id=UNHEX(?)",id);
        var reservation=jdbc.queryForMap("SELECT * FROM reservations WHERE id=UNHEX(?)",id);
        align();
        assertThat(jdbc.queryForMap("SELECT * FROM payments WHERE id=UNHEX(?)",id)).usingRecursiveComparison().isEqualTo(payment);
        assertThat(jdbc.queryForMap("SELECT * FROM reservations WHERE id=UNHEX(?)",id)).usingRecursiveComparison().isEqualTo(reservation);
        assertThat(columnCount()).isEqualTo(10);
    }
    @Test void partialExactSchemaGetsOnlyMissingColumns() throws Exception {
        minimalTables();jdbc.execute("ALTER TABLE payments ADD COLUMN gateway_provider VARCHAR(32) NULL");
        align();assertThat(columnCount()).isEqualTo(10);
    }
    @Test void incompatibleLastColumnStopsBeforeAnyAlignmentDdl() {
        minimalTables();jdbc.execute("ALTER TABLE reservations ADD COLUMN appointment_at VARCHAR(50) NULL");
        assertThatThrownBy(this::align).isInstanceOf(SQLException.class).hasMessageContaining("Incompatible reservations.appointment_at");
        assertThat(columnCount()).isEqualTo(1);
    }
    @Test void wrongRefundDefaultStopsWithoutAddingEarlierColumns() {
        minimalTables();jdbc.execute("ALTER TABLE payments ADD COLUMN refunded_amount DECIMAL(14,2) NOT NULL DEFAULT 1.00");
        assertThatThrownBy(this::align).isInstanceOf(SQLException.class).hasMessageContaining("Incompatible payments.refunded_amount");
        assertThat(columnCount()).isEqualTo(1);
    }
}
