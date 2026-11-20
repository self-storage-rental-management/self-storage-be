package com.storagehub.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class FlywayMigrationVersionsTests {
    @Test
    void defaultLocationsResolveWithoutDuplicateVersions() throws IOException {
        var properties = new Properties();
        try (var input = getClass().getResourceAsStream("/application.properties")) {
            properties.load(input);
        }
        String[] locations = new MockEnvironment()
            .resolveRequiredPlaceholders(properties.getProperty("spring.flyway.locations"))
            .split(",");

        var flyway = Flyway.configure()
            .dataSource("jdbc:h2:mem:migration_versions", "sa", "")
            .locations(locations)
            .load();

        assertThat(flyway.info().pending()).isNotEmpty();
        assertThat(flyway.info().all())
            .anyMatch(migration -> migration.getVersion() != null
                && "2026111803".equals(migration.getVersion().getVersion())
                && migration.getDescription().contains("pallet rating catalog"));
    }
}
