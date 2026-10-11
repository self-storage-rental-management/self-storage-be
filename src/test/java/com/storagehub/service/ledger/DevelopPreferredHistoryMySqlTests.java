package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.config.DevelopMigrationHistoryLocations;
import com.storagehub.config.LedgerMigrationSafetyCallback;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@EnabledIfEnvironmentVariable(named="STORAGEHUB_SCHEMA_TEST_URL",matches=".+")
class DevelopPreferredHistoryMySqlTests {
    HikariDataSource datasource;JdbcTemplate jdbc;
    @BeforeEach void isolatedDatabase(){
        String url=System.getenv("STORAGEHUB_SCHEMA_TEST_URL");
        assertThat(url).matches("^jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/storagehub_schema_test_[a-z0-9_]+(?:\\?.*)?$");
        String database="storagehub_schema_test_history_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        try(var admin=new HikariDataSource()){
            admin.setJdbcUrl(url);admin.setUsername(System.getenv("STORAGEHUB_SCHEMA_TEST_USERNAME"));admin.setPassword(System.getenv("STORAGEHUB_SCHEMA_TEST_PASSWORD"));
            new JdbcTemplate(admin).execute("CREATE DATABASE "+database);
        }
        datasource=new HikariDataSource();datasource.setJdbcUrl(url.replaceFirst("/storagehub_schema_test_[a-z0-9_]+","/"+database));
        datasource.setUsername(System.getenv("STORAGEHUB_SCHEMA_TEST_USERNAME"));datasource.setPassword(System.getenv("STORAGEHUB_SCHEMA_TEST_PASSWORD"));jdbc=new JdbcTemplate(datasource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class)).isZero();
        System.out.println("Develop history isolated DB: "+database);
    }
    @AfterEach void close(){if(datasource!=null)datasource.close();}
    Flyway selected(){return DevelopMigrationHistoryLocations.configure(Flyway.configure().dataSource(datasource)
        .locations("classpath:db/migration").callbacks(new LedgerMigrationSafetyCallback()).cleanDisabled(true).outOfOrder(false)).load();}
    void history(String... folders){
        String[] locations=new String[folders.length+1];locations[0]="classpath:db/migration";
        for(int index=0;index<folders.length;index++)locations[index+1]="classpath:db/migration-history/"+folders[index];
        Flyway.configure().dataSource(datasource).locations(locations).callbacks(new LedgerMigrationSafetyCallback())
            .target("2026101001").outOfOrder(false).cleanDisabled(true).load().migrate();
    }
    @Test void developHistoryUpgradesAndReplaysWithoutVnpayDuplicateOrHistoryChange(){
        history("develop-0909","develop-1001");
        var before=jdbc.queryForList("SELECT version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        var flyway=selected();flyway.migrate();flyway.validate();flyway.migrate();
        assertThat(jdbc.queryForList("SELECT version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank")).containsAll(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='2026100910'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Long.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT script FROM flyway_schema_history WHERE version='2026101001'",String.class)).isEqualTo("V2026101001__add_customer_checkin_appointment.sql");
    }
    @Test void alreadyApplied0910IsResolvedButNeverReexecuted(){
        history("legacy-0909","applied-vnpay-0910","develop-1001");
        var before=jdbc.queryForList("SELECT version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
        var flyway=selected();flyway.migrate();flyway.validate();flyway.migrate();
        assertThat(jdbc.queryForList("SELECT version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank")).containsAll(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='2026100910'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Long.class)).isEqualTo(16);
    }
    @Test void legacyLedgerSchemaDriftIsBlockedRatherThanRecreated(){
        history("legacy-0909","legacy-1001");
        jdbc.execute("ALTER TABLE rental_ledger_accounts ADD COLUMN unexpected_column INT NULL");
        assertThatThrownBy(()->selected().migrate()).hasStackTraceContaining("schema fingerprint differs");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='rental_ledger_accounts' AND column_name='unexpected_column'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='2026101103' AND success=1",Long.class)).isZero();
    }
    @Test void sevenOrphanedTablesDoNotBecomeAProvenLegacyLedger() throws Exception {
        history("develop-0909","develop-1001");
        try(var connection=datasource.getConnection()){
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration-history/legacy-1001/V2026101001__create_rental_ledger_and_notification_outbox.sql"));
        }
        assertThatThrownBy(()->selected().migrate()).hasStackTraceContaining("without matching applied legacy history");
    }
}
