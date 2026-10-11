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
            var flyway=Flyway.configure().dataSource(datasource).locations("classpath:db/migration","classpath:db/paymentmigration")
                .callbacks(new LedgerMigrationSafetyCallback()).baselineOnMigrate(false).cleanDisabled(true).load();
            flyway.validate();assertThat(flyway.info().pending()).isEmpty();flyway.migrate();
            assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history",Long.class)).isEqualTo(before);
        }
    }
}
