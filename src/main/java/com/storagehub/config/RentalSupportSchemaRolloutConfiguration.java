package com.storagehub.config;

import com.storagehub.service.communication.CommunicationSchemaGuard;
import com.storagehub.service.ledger.LedgerSchemaGuard;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import javax.sql.DataSource;
import org.flywaydb.core.api.MigrationState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit rollout verifies the intended target; default ledger migration has its own safety callback. */
@Configuration @Profile("rental-support-schema")
public class RentalSupportSchemaRolloutConfiguration {
    @Bean
    public FlywayMigrationStrategy rentalSupportSchemaMigration(
        @Value("${storagehub.schema-rollout.expected-database}") String expectedDatabase) {
        return flyway -> {
            var datasource=flyway.getConfiguration().getDataSource();
            verifyTarget(datasource,expectedDatabase);
            var jdbc=new JdbcTemplate(datasource);
            long existing=jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name in ('rental_ledger_accounts','rental_ledger_obligations','rental_ledger_receipts','rental_ledger_allocations','rental_ledger_refunds','rental_ledger_commands','notification_outbox')",Long.class);
            boolean installed=Arrays.stream(flyway.info().applied()).anyMatch(info -> info.getVersion()!=null
                && ("2026101103".equals(info.getVersion().toString())
                    || "2026101001".equals(info.getVersion().toString()) && info.getScript()!=null
                    && info.getScript().endsWith("V2026101001__create_rental_ledger_and_notification_outbox.sql"))
                && (info.getState()==MigrationState.SUCCESS || info.getState()==MigrationState.OUT_OF_ORDER));
            if(existing>0 && (!installed || existing!=7))
                throw new IllegalStateException("Schema rollout stopped: existing integration tables/history need reconciliation; no repair/reset performed");
            // Flyway validates checksums before executing pending migrations. Never baseline or clean here.
            flyway.migrate();
            new LedgerSchemaGuard(jdbc).afterPropertiesSet();
            new CommunicationSchemaGuard(jdbc).afterPropertiesSet();
        };
    }

    /** SELECT only; the actual connection identity must match the operator's declared database. */
    public static void verifyTarget(DataSource datasource,String expectedDatabase) {
        if(expectedDatabase==null || !expectedDatabase.matches("[A-Za-z0-9_]+"))
            throw new IllegalStateException("Schema rollout requires an explicit expected database name");
        try(var connection=datasource.getConnection();var statement=connection.createStatement();
            var result=statement.executeQuery("select version(), database()")) {
            if(!result.next())throw new IllegalStateException("Schema rollout cannot verify database identity");
            var version=result.getString(1);var database=result.getString(2);
            if(!expectedDatabase.equals(database))
                throw new IllegalStateException("Schema rollout stopped: connected database does not match the expected target");
            // This migration's generated binary foreign keys have only been verified on MySQL 8.4.
            if(version==null || version.toLowerCase(Locale.ROOT).contains("tidb")
                || !version.matches("8\\.4\\.[0-9]+(?:[-.].*)?")
                || !"MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName()))
                throw new IllegalStateException("Schema rollout stopped: target engine/version is not verified MySQL 8.4; certify this target separately");
        } catch(SQLException e) {
            throw new IllegalStateException("Schema rollout cannot verify its database connection",e);
        }
    }
}
