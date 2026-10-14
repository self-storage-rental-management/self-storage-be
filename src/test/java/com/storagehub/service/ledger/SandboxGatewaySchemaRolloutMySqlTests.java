package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;

import com.storagehub.config.DevelopMigrationHistoryLocations;
import com.storagehub.config.LedgerMigrationSafetyCallback;
import com.storagehub.config.SandboxLedgerSchemaRolloutConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit MySQL 8.4 test schema only. Never targets the application or team database. */
@EnabledIfEnvironmentVariable(named="STORAGEHUB_SCHEMA_TEST_URL",matches=".+")
class SandboxGatewaySchemaRolloutMySqlTests {
    private static HikariDataSource datasource;
    private static JdbcTemplate jdbc;

    @BeforeAll static void connectToEmptyIsolatedSchema() {
        String url = System.getenv("STORAGEHUB_SCHEMA_TEST_URL");
        assertThat(url).matches("^jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/storagehub_schema_test_[a-z0-9_]+(?:\\?.*)?$");
        datasource = new HikariDataSource();
        datasource.setJdbcUrl(url);
        datasource.setUsername(System.getenv("STORAGEHUB_SCHEMA_TEST_USERNAME"));
        datasource.setPassword(System.getenv("STORAGEHUB_SCHEMA_TEST_PASSWORD"));
        datasource.setMaximumPoolSize(2);
        jdbc = new JdbcTemplate(datasource);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database()",Long.class))
            .as("Refuse an existing database; the test must own a fresh empty schema").isZero();
    }

    @AfterAll static void close() { if (datasource != null) datasource.close(); }

    @Test void migrationInstallsGatewayConstraintsAndRerunIsNoop() {
        Flyway flyway = DevelopMigrationHistoryLocations.configure(Flyway.configure().dataSource(datasource)
            .locations("classpath:db/migration","classpath:db/paymentmigration","classpath:db/ledger-sandbox-migration")
            .callbacks(new LedgerMigrationSafetyCallback())
            .baselineOnMigrate(false).cleanDisabled(true).outOfOrder(false)).load();
        String expected = jdbc.queryForObject("select database()",String.class);
        var rollout = new SandboxLedgerSchemaRolloutConfiguration().sandboxLedgerSchemaMigration(expected);
        rollout.migrate(flyway);
        flyway.validate();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='2026101202' and success=1",Long.class)).isEqualTo(1);
        new LedgerSchemaGuard(jdbc,true).afterPropertiesSet();
        new SandboxGatewaySchemaGuard(jdbc).afterPropertiesSet();
        long before = jdbc.queryForObject("select count(*) from flyway_schema_history",Long.class);
        rollout.migrate(flyway);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history",Long.class)).isEqualTo(before);

        // An independently changed uniqueness constraint must block credit on the next startup.
        jdbc.execute("alter table rental_ledger_gateway_evidence drop index payment_id");
        try {
            assertThatThrownBy(() -> new SandboxGatewaySchemaGuard(jdbc).afterPropertiesSet())
                .hasMessageContaining("unique gateway payment_id");
        } finally {
            jdbc.execute("alter table rental_ledger_gateway_evidence add unique index payment_id(payment_id)");
        }
        new SandboxGatewaySchemaGuard(jdbc).afterPropertiesSet();
    }
}
