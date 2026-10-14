package com.storagehub.config;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.stereotype.Component;
import org.flywaydb.core.api.migration.JavaMigration;

/** SELECT-only routing of immutable historical SQL; never changes Flyway history. */
@Component
public class DevelopMigrationHistoryLocations implements FlywayConfigurationCustomizer {
    private record Applied(String script, String type, boolean success) {}
    @Override public void customize(FluentConfiguration configuration) { configure(configuration); }

    public static FluentConfiguration configure(FluentConfiguration configuration) {
        var locations = Arrays.stream(configuration.getLocations()).map(Object::toString).toList();
        if (!locations.contains("classpath:db/migration")) return configuration;
        if (configuration.getDataSource() == null) throw new IllegalStateException("Migration history routing requires the actual Flyway datasource");
        String history = configuration.getTable();
        if (!history.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Unsupported Flyway history table identifier");
        Map<String,Applied> applied = new HashMap<>();
        try (var connection = configuration.getDataSource().getConnection()) {
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?")) {
                query.setString(1, history);
                try (var result = query.executeQuery()) {
                    if (result.next() && result.getInt(1) > 0) {
                        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT version,script,type,success FROM `" + history + "` WHERE version IN ('2026100909','2026100910','2026101001','2026101202','2026101203','2026101301','2026101302')")) {
                            while (rows.next()) {
                                var value = new Applied(rows.getString("script"),rows.getString("type"),rows.getBoolean("success"));
                                if (applied.put(rows.getString("version"), value) != null) throw new IllegalStateException("Duplicate version in applied migration history; no repair performed");
                            }
                        }
                    }
                }
            }
        } catch (SQLException error) { throw new IllegalStateException("Cannot inspect actual Flyway history; no migration performed", error); }
        var selected = new ArrayList<>(locations.stream().filter(location -> !location.startsWith("classpath:db/migration-history/")).toList());
        // Resolve the original immutable SQL only on databases that actually applied it.
        // Never install version 1202 on a fresh database or collide with another 1202 history.
        var historicalGateway = applied.get("2026101202");
        boolean legacyBinding = historicalGateway != null && "V2026101202__add_renewal_vnpay_resource_binding.sql".equals(historicalGateway.script());
        if (legacyBinding) {
            require(historicalGateway, "V2026101202__add_renewal_vnpay_resource_binding.sql");
            selected.add("classpath:db/migration-history/legacy-binding-1202");
            // Some local histories already contain both identities. Resolve both immutable
            // resources for checksum validation; neither applied version is executed again.
            if (applied.containsKey("2026101203")) {
                require(applied.get("2026101203"), "V2026101203__add_renewal_vnpay_resource_binding.sql");
                selected.add("classpath:db/migration-history/develop-1203");
            }
        } else {
            if (applied.containsKey("2026101203")) require(applied.get("2026101203"), "V2026101203__add_renewal_vnpay_resource_binding.sql");
            selected.add("classpath:db/migration-history/develop-1203");
        }
        if (historicalGateway != null && "V2026101202__sandbox_gateway_ledger_evidence.sql".equals(historicalGateway.script())) {
            require(historicalGateway, "V2026101202__sandbox_gateway_ledger_evidence.sql");
            if (!selected.contains("classpath:db/ledger-sandbox-migration")) selected.add("classpath:db/ledger-sandbox-migration");
        }
        selected.add(select(applied, "2026100909", "V2026100909__add_vnpay_payment_fields.sql", "develop-0909", "V2026100909__remove_legacy_rental_end_date.sql", "legacy-0909"));
        selected.add(select(applied, "2026101001", "V2026101001__add_customer_checkin_appointment.sql", "develop-1001", "V2026101001__create_rental_ledger_and_notification_outbox.sql", "legacy-1001"));
        selected.add(select(applied,"2026101301","V2026101301__deactivate_duplicate_effective_rental_packages.sql","develop-1301",
            "V2026101301__create_staff_renewal_signing_calendar.sql","legacy-calendar-1301"));
        var gateway=applied.get("2026101302");
        boolean legacyGateway=gateway!=null&&"db.sandboxpaymentmigration.V2026101302__ensure_gateway_ledger_evidence".equals(gateway.script());
        if(legacyGateway) {
            if(!gateway.success()||!"JDBC".equals(gateway.type()))throw new IllegalStateException("Unrecognized gateway history; no repair performed");
        } else {
            if(gateway!=null)require(gateway,"V2026101302__add_facility_operating_details.sql");
            selected.add("classpath:db/migration-history/develop-1302");
        }
        boolean enableGateway=locations.contains("classpath:db/sandboxpaymentmigration");
        // Keep the exact old Java class identity only where it was actually applied.
        // Fresh or develop databases resolve the gateway at a unique new version.
        selected.remove("classpath:db/sandboxpaymentmigration");
        if(selected.remove("classpath:db/renewal-calendar-migration"))selected.add("classpath:db/renewalcalendarmigration");
        var javaMigrations=new ArrayList<JavaMigration>(Arrays.stream(configuration.getJavaMigrations())
            .filter(m->!(m instanceof db.sandboxpaymentmigration.V2026101302__ensure_gateway_ledger_evidence)).toList());
        if(legacyGateway) javaMigrations.add(new db.sandboxpaymentmigration.V2026101302__ensure_gateway_ledger_evidence());
        if(enableGateway) javaMigrations.add(new db.renewalpaymentmigration.V2026101404__ensure_gateway_ledger_evidence());
        configuration.javaMigrations(javaMigrations.toArray(JavaMigration[]::new));
        if(legacyBinding||legacyGateway||applied.containsKey("2026101301")&&"V2026101301__create_staff_renewal_signing_calendar.sql".equals(applied.get("2026101301").script()))
            configuration.outOfOrder(true); // Apply new develop migrations older than the already installed feature migrations.
        if (applied.containsKey("2026100910")) {
            require(applied.get("2026100910"), "V2026100910__add_vnpay_payment_fields.sql");
            selected.add("classpath:db/migration-history/applied-vnpay-0910");
        }
        return configuration.locations(selected.toArray(String[]::new));
    }
    private static String select(Map<String,Applied> applied,String version,String developScript,String developFolder,String legacyScript,String legacyFolder) {
        var value=applied.get(version);
        if(value==null)return "classpath:db/migration-history/"+developFolder;
        if(developScript.equals(value.script())) {require(value,developScript);return "classpath:db/migration-history/"+developFolder;}
        require(value,legacyScript);return "classpath:db/migration-history/"+legacyFolder;
    }
    private static void require(Applied value,String script) {
        if(!value.success()||!"SQL".equals(value.type())||!script.equals(value.script()))
            throw new IllegalStateException("Unrecognized or failed applied migration history; stop rollout without repair/reset");
    }
}
