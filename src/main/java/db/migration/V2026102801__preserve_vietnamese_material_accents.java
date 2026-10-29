package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** MySQL's accent-insensitive default must not merge the Vietnamese materials Da and Đá. */
public class V2026102801__preserve_vietnamese_material_accents extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (connection.getMetaData().getDatabaseProductName().equals("MySQL")) {
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE goods_material_catalog MODIFY COLUMN display_name "
                    + "VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci NOT NULL");
            }
        }
        byte[] furniture;
        try (var statement = connection.prepareStatement("SELECT id FROM goods_type_catalog WHERE code = 'FURNITURE' AND status = 'ACTIVE'");
             var rows = statement.executeQuery()) {
            if (!rows.next()) return;
            furniture = rows.getBytes(1);
        }
        // Use Java comparison as well so the repair never relies on database accent rules.
        try (var statement = connection.prepareStatement("SELECT display_name FROM goods_material_catalog WHERE goods_type_id = ?")) {
            statement.setBytes(1, furniture);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) if ("Đá".equalsIgnoreCase(rows.getString(1))) return;
            }
        }
        Set<String> previousCategories = activeIds(connection, "goods_type_catalog");
        Set<String> previousMaterials = activeIds(connection, "goods_material_catalog");
        UUID stone = UUID.randomUUID();
        try (var statement = connection.prepareStatement("INSERT INTO goods_material_catalog "
            + "(id, goods_type_id, code, display_name, fragile, status, sort_order, version, created_at, updated_at) "
            + "VALUES (?, ?, ?, 'Đá', FALSE, 'ACTIVE', 70, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))")) {
            statement.setBytes(1, bytes(stone));
            statement.setBytes(2, furniture);
            statement.setString(3, "MAT-" + stone.toString().replace("-", "").substring(0, 12).toUpperCase());
            statement.executeUpdate();
        }
        ObjectMapper mapper = new ObjectMapper();
        record Scope(byte[] id, String json) {}
        var scopes = new ArrayList<Scope>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id, accepted_goods_json FROM unit_types")) {
            while (rows.next()) scopes.add(new Scope(rows.getBytes(1), rows.getString(2)));
        }
        for (var scope : scopes) {
            if (scope.json() == null) continue;
            ObjectNode json = (ObjectNode) mapper.readTree(scope.json());
            Set<String> categories = ids(json, "categoryIds");
            Set<String> materials = ids(json, "materialIds");
            if (materials.isEmpty() || !materials.containsAll(previousMaterials)
                || (!categories.isEmpty() && !categories.containsAll(previousCategories))) continue;
            ((ArrayNode) json.get("materialIds")).add(stone.toString());
            try (var statement = connection.prepareStatement("UPDATE unit_types SET accepted_goods_json = ? WHERE id = ?")) {
                statement.setString(1, mapper.writeValueAsString(json));
                statement.setBytes(2, scope.id());
                statement.executeUpdate();
            }
        }
    }

    private static Set<String> ids(ObjectNode json, String key) {
        Set<String> result = new HashSet<>();
        json.path(key).forEach(value -> result.add(value.asText().toLowerCase(java.util.Locale.ROOT)));
        return result;
    }

    private static Set<String> activeIds(Connection connection, String table) throws Exception {
        Set<String> result = new HashSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id FROM " + table + " WHERE status = 'ACTIVE'")) {
            while (rows.next()) {
                ByteBuffer buffer = ByteBuffer.wrap(rows.getBytes(1));
                result.add(new UUID(buffer.getLong(), buffer.getLong()).toString());
            }
        }
        return result;
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
