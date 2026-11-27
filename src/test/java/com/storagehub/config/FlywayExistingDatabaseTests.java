package com.storagehub.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Runs only against an explicitly supplied disposable copy of an existing database. */
@EnabledIfEnvironmentVariable(named = "STORAGEHUB_FLYWAY_TEST_URL", matches = ".+")
class FlywayExistingDatabaseTests {
    @Test void migrateCopiedHistory() {
        String url = System.getenv("STORAGEHUB_FLYWAY_TEST_URL");
        if (!url.matches("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1)(?::[0-9]+)?/codex_flyway_test_[A-Za-z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("Migration test requires a local disposable codex_flyway_test database");
        var config = Flyway.configure()
            .dataSource(url,
                System.getenv("STORAGEHUB_FLYWAY_TEST_USER"),
                System.getenv("STORAGEHUB_FLYWAY_TEST_PASSWORD"))
            .locations("classpath:db/migration", "classpath:db/paymentmigration",
                "classpath:db/renewal-hold-migration", "classpath:db/ledger-sandbox-migration",
                "classpath:db/renewal-local-demo-migration", "classpath:db/sandboxpaymentmigration")
            .baselineOnMigrate(true).baselineVersion("2026100200").outOfOrder(true);
        var flyway = DevelopMigrationHistoryLocations.configure(config).load();
        flyway.migrate();
        flyway.validate();
    }
}
