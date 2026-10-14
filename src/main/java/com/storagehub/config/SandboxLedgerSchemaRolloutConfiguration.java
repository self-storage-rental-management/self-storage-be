package com.storagehub.config;

import com.storagehub.service.ledger.LedgerSchemaGuard;
import com.storagehub.service.ledger.SandboxGatewaySchemaGuard;
import java.util.Arrays;
import org.flywaydb.core.api.MigrationState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** Operator-selected rollout. A missing Flyway history entry never authorizes repair of an existing gateway table. */
@Configuration @Profile("sandbox-ledger-schema")
public class SandboxLedgerSchemaRolloutConfiguration {
    @Bean
    public FlywayMigrationStrategy sandboxLedgerSchemaMigration(
        @Value("${storagehub.schema-rollout.expected-database}") String expectedDatabase) {
        return flyway -> {
            var datasource = flyway.getConfiguration().getDataSource();
            RentalSupportSchemaRolloutConfiguration.verifyTarget(datasource, expectedDatabase);
            var jdbc = new JdbcTemplate(datasource);
            long existing = jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name='rental_ledger_gateway_evidence'", Long.class);
            boolean installed = Arrays.stream(flyway.info().applied()).anyMatch(info -> info.getVersion() != null
                && "2026101202".equals(info.getVersion().toString())
                && "V2026101202__sandbox_gateway_ledger_evidence.sql".equals(info.getScript())
                && (info.getState() == MigrationState.SUCCESS || info.getState() == MigrationState.OUT_OF_ORDER));
            if ((existing == 0 && installed) || (existing != 0 && !installed))
                throw new IllegalStateException("Sandbox ledger rollout stopped: gateway table/history mismatch; no repair performed");
            flyway.migrate();
            new LedgerSchemaGuard(jdbc, true).afterPropertiesSet();
            new SandboxGatewaySchemaGuard(jdbc).afterPropertiesSet();
        };
    }
}
