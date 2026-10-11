package com.storagehub.config;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

/** Protects only the pending seven-table migration, not unrelated migrations or feature flags. */
@Component
public class LedgerMigrationSafetyCallback implements Callback {
    @Override public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE && context.getMigrationInfo() != null
            && context.getMigrationInfo().getVersion() != null
            && "2026101001".equals(context.getMigrationInfo().getVersion().toString())
            && context.getMigrationInfo().getScript()!=null
            && context.getMigrationInfo().getScript().endsWith("V2026101001__create_rental_ledger_and_notification_outbox.sql");
    }
    @Override public boolean canHandleInTransaction(Event event, Context context) { return true; }
    @Override public String getCallbackName() { return "rental-ledger-schema-safety"; }
    @Override public void handle(Event event, Context context) {
        if (!supports(event, context)) return;
        var datasource = new SingleConnectionDataSource(context.getConnection(), true);
        var jdbc = new JdbcTemplate(datasource);
        String database = jdbc.queryForObject("select database()", String.class);
        RentalSupportSchemaRolloutConfiguration.verifyTarget(datasource, database);
        long existing = jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name in ('rental_ledger_accounts','rental_ledger_obligations','rental_ledger_receipts','rental_ledger_allocations','rental_ledger_refunds','rental_ledger_commands','notification_outbox')", Long.class);
        if (existing != 0) {
            throw new IllegalStateException("Ledger migration stopped: integration tables already exist without this applied migration; reconcile history/schema, no repair or reset performed");
        }
    }
}
