package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.config.LedgerMigrationSafetyCallback;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named="STORAGEHUB_SCHEMA_RELOCATION_TEST_URL",matches=".+")
class LedgerMigrationRelocationMySqlTests {
    @Test void existingOptInHistoryValidatesAtCommonLocationWithoutReapplying() {
        String url=System.getenv("STORAGEHUB_SCHEMA_RELOCATION_TEST_URL");
        assertThat(url).matches("^jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/storagehub_schema_test_[a-z0-9_]+(?:\\?.*)?$");
        try(var datasource=new HikariDataSource()) {
            datasource.setJdbcUrl(url);datasource.setUsername(System.getenv("STORAGEHUB_SCHEMA_TEST_USERNAME"));datasource.setPassword(System.getenv("STORAGEHUB_SCHEMA_TEST_PASSWORD"));
            var jdbc=new JdbcTemplate(datasource);
            assertThat(jdbc.queryForObject("select checksum from flyway_schema_history where version='2026101001' and success=1",Integer.class)).isEqualTo(-1198625055);
            long before=jdbc.queryForObject("select count(*) from flyway_schema_history",Long.class);
            var flyway=com.storagehub.config.DevelopMigrationHistoryLocations.configure(Flyway.configure().dataSource(datasource).locations("classpath:db/migration","classpath:db/paymentmigration")
                .callbacks(new LedgerMigrationSafetyCallback()).baselineOnMigrate(false).cleanDisabled(true).outOfOrder(false)).load();
            var oldHistory=jdbc.queryForList("select version,script,checksum,success from flyway_schema_history order by installed_rank");
            assertThat(flyway.getConfiguration().isValidateOnMigrate()).isTrue();
            assertThat(flyway.info().pending()).allSatisfy(info -> assertThat(info.getVersion().toString()).isIn("2026101102","2026101103"));
            int pending=flyway.info().pending().length;
            flyway.migrate();flyway.validate();flyway.migrate();
            assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history",Long.class)).isEqualTo(before+pending);
            assertThat(jdbc.queryForList("select version,script,checksum,success from flyway_schema_history order by installed_rank"))
                .containsAll(oldHistory);
        }
    }
}
