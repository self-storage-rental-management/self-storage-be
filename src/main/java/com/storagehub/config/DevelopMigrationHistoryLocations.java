package com.storagehub.config;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.stereotype.Component;

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
                        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT version,script,type,success FROM `" + history + "` WHERE version IN ('2026100909','2026100910','2026101001')")) {
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
        selected.add(select(applied, "2026100909", "V2026100909__add_vnpay_payment_fields.sql", "develop-0909", "V2026100909__remove_legacy_rental_end_date.sql", "legacy-0909"));
        selected.add(select(applied, "2026101001", "V2026101001__add_customer_checkin_appointment.sql", "develop-1001", "V2026101001__create_rental_ledger_and_notification_outbox.sql", "legacy-1001"));
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
