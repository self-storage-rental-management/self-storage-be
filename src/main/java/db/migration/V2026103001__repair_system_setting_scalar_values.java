package db.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Repairs legacy system settings that were persisted as JsonNode metadata objects. */
public class V2026103001__repair_system_setting_scalar_values extends BaseJavaMigration {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Definition> DEFINITIONS = Map.ofEntries(
        entry("businessName", "text", "\"StorageHub Vietnam\""),
        entry("contactEmail", "text", "\"support@storagehub.demo\""),
        entry("hotline", "text", "\"+84 28 3822 8888\""),
        entry("currency", "select", "\"VND (₫)\"", "VND (₫)"),
        entry("timezone", "select", "\"GMT+7 (Asia/Ho_Chi_Minh)\"", "GMT+7 (Asia/Ho_Chi_Minh)", "GMT+8 (Asia/Singapore)", "GMT+0 (UTC)"),
        entry("gracePeriod", "number", "5"),
        entry("lateFeeAmount", "number", "650000"),
        entry("autoInvoiceDays", "select", "\"7 days before due date\"", "3 days before due date", "7 days before due date", "14 days before due date", "30 days before due date"),
        entry("autoProrate", "toggle", "true"),
        entry("require2FA", "toggle", "true"),
        entry("pinRotation", "select", "\"90 days\"", "30 days", "60 days", "90 days", "Never (Manual)"),
        entry("lockoutAttempts", "select", "\"3 failed attempts\"", "3 failed attempts", "5 failed attempts", "10 failed attempts"),
        entry("sessionTimeout", "select", "\"30 minutes\"", "15 minutes", "30 minutes", "60 minutes", "4 hours"),
        entry("emailAlerts", "toggle", "true"),
        entry("smsGateAlerts", "toggle", "false"),
        entry("slackWebhook", "text", "\"https://hooks.slack.com/services/T00/B00/XXXX\""),
        entry("dailyDigest", "toggle", "true"),
        entry("maintenanceMode", "toggle", "false"),
        entry("bannerNotice", "text", "\"Routine fire alarm testing scheduled this Sunday 9:00 AM - 11:00 AM.\"")
    );

    @Override
    public Integer getChecksum() {
        return 2026103001;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean hasLegacyValue = hasColumn(connection, "value");
        String selectSql = "SELECT setting_key, setting_type, setting_value"
            + (hasLegacyValue ? ", value" : "")
            + " FROM system_settings";

        try (PreparedStatement select = connection.prepareStatement(selectSql);
             ResultSet rows = select.executeQuery();
             PreparedStatement update = connection.prepareStatement(
                 "UPDATE system_settings SET setting_value = ? WHERE setting_key = ?")) {
            while (rows.next()) {
                String key = rows.getString("setting_key");
                Definition definition = DEFINITIONS.get(key);
                if (definition == null) continue;

                String current = rows.getString("setting_value");
                JsonNode value = validValue(current, definition) ? parse(current) : null;
                if (value == null && hasLegacyValue) {
                    String legacy = rows.getString("value");
                    value = validValue(legacy, definition) ? parse(legacy) : null;
                }
                if (value == null) value = parse(definition.defaultJson());

                String normalized = value.toString();
                if (!normalized.equals(current)) {
                    update.setString(1, normalized);
                    update.setString(2, key);
                    update.addBatch();
                }
            }
            update.executeBatch();
        }
    }

    private static boolean validValue(String raw, Definition definition) {
        if (raw == null || raw.isBlank()) return false;
        JsonNode value = parse(raw);
        if (value == null) return false;
        return switch (definition.type()) {
            case "text" -> value.isTextual();
            case "select" -> value.isTextual() && definition.options().contains(value.textValue());
            case "number" -> value.isNumber();
            case "toggle" -> value.isBoolean();
            default -> false;
        };
    }

    private static JsonNode parse(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasColumn(Connection connection, String column) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'system_settings' AND column_name = ?")) {
            query.setString(1, column);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1) == 1;
            }
        }
    }

    private static Map.Entry<String, Definition> entry(String key, String type, String defaultJson, String... options) {
        return Map.entry(key, new Definition(type, defaultJson, List.of(options)));
    }

    private record Definition(String type, String defaultJson, List<String> options) {
    }
}
