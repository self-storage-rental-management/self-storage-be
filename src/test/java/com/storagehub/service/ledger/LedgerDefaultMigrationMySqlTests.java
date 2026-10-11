package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.config.LedgerMigrationSafetyCallback;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named="STORAGEHUB_SCHEMA_TEST_URL",matches=".+")
class LedgerDefaultMigrationMySqlTests {
    HikariDataSource datasource;JdbcTemplate jdbc;
    @BeforeEach void freshTestDatabase() {
        String url=System.getenv("STORAGEHUB_SCHEMA_TEST_URL");
        assertThat(url).matches("^jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/storagehub_schema_test_[a-z0-9_]+(?:\\?.*)?$");
        String database="storagehub_schema_test_ledger_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=new HikariDataSource()) {
            admin.setJdbcUrl(url);admin.setUsername(System.getenv("STORAGEHUB_SCHEMA_TEST_USERNAME"));admin.setPassword(System.getenv("STORAGEHUB_SCHEMA_TEST_PASSWORD"));
            new JdbcTemplate(admin).execute("create database "+database);
        }
        datasource=new HikariDataSource();datasource.setJdbcUrl(url.replaceFirst("/storagehub_schema_test_[a-z0-9_]+","/"+database));
        datasource.setUsername(System.getenv("STORAGEHUB_SCHEMA_TEST_USERNAME"));datasource.setPassword(System.getenv("STORAGEHUB_SCHEMA_TEST_PASSWORD"));
        datasource.setMaximumPoolSize(2);jdbc=new JdbcTemplate(datasource);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database()",Long.class)).isZero();
        System.out.println("Default ledger migration isolated DB: "+database);
    }
    @AfterEach void close(){if(datasource!=null)datasource.close();}
    Flyway common(){return com.storagehub.config.DevelopMigrationHistoryLocations.configure(Flyway.configure().dataSource(datasource).locations("classpath:db/migration")
        .callbacks(new LedgerMigrationSafetyCallback()).baselineOnMigrate(false).cleanDisabled(true).outOfOrder(false)).load();}
    void oldBase(){Flyway.configure().configuration(common().getConfiguration()).target("2026100909").load().migrate();}
    @Test void defaultLocationInstallsSevenTablesWithoutPaymentOptIn() {
        var flyway=common();flyway.migrate();flyway.validate();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success=1",Long.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("select checksum from flyway_schema_history where version='2026101103'",Integer.class)).isEqualTo(2026101103);
        assertThat(jdbc.queryForObject("select script from flyway_schema_history where version='2026101001'",String.class)).isEqualTo("V2026101001__add_customer_checkin_appointment.sql");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='2026101101'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name in ('rental_ledger_accounts','rental_ledger_obligations','rental_ledger_receipts','rental_ledger_allocations','rental_ledger_refunds','rental_ledger_commands','notification_outbox')",Long.class)).isEqualTo(7);
    }
    @Test void oldDatabaseUpgradesWithoutChangingExistingUserAndReplayIsNoop() {
        oldBase();String id=UUID.randomUUID().toString().replace("-","");
        jdbc.update("insert into users(id,created_at,updated_at,email,password_hash,full_name,status,must_change_password) values(unhex(?),now(6),now(6),?,'TEST-ONLY','EXISTING USER','ACTIVE',false)",id,id+"@isolated.invalid");
        var before=jdbc.queryForMap("select * from users where id=unhex(?)",id);
        var flyway=common();flyway.migrate();flyway.validate();flyway.migrate();
        assertThat(jdbc.queryForMap("select * from users where id=unhex(?)",id)).usingRecursiveComparison().isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='2026101103' and success=1",Long.class)).isEqualTo(1);
    }
    @Test void preexistingLedgerTableWithoutAppliedHistoryFailsBeforeOtherLedgerDdl() {
        oldBase();jdbc.execute("create table rental_ledger_accounts(id bigint primary key)");
        assertThatThrownBy(()->common().migrate()).hasStackTraceContaining("integration tables already exist");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name in ('rental_ledger_accounts','rental_ledger_obligations','rental_ledger_receipts','rental_ledger_allocations','rental_ledger_refunds','rental_ledger_commands','notification_outbox')",Long.class)).isEqualTo(1);
    }
}
