package db.migration;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Replaces the active goods catalog while retaining archived rows for history. */
public class V2026102703__replace_goods_catalog extends BaseJavaMigration {
    private static final Map<String, Category> CATALOG = new LinkedHashMap<>();

    static {
        CATALOG.put("FURNITURE", category("Nội thất", "Gỗ", "Kim loại", "Nhựa", "Vải", "Da", "Kính/Thủy tinh", "Đá"));
        CATALOG.put("KITCHENWARE", category("Đồ dùng nhà bếp", "Inox", "Gang", "Nhôm", "Nhựa", "Gỗ", "Kính/Thủy tinh", "Gốm/Sứ"));
        CATALOG.put("DECOR", category("Vật dụng trang trí", "Gỗ", "Kim loại", "Nhựa", "Vải", "Kính/Thủy tinh", "Gốm/Sứ", "Đá"));
        CATALOG.put("DOCUMENTS", category("Sách & hồ sơ", "Giấy", "Carton"));
        CATALOG.put("TOYS", category("Đồ chơi", "Nhựa", "Gỗ", "Vải", "Cao su", "Kim loại"));
        CATALOG.put("SPORTS", category("Dụng cụ thể thao", "Kim loại", "Cao su", "Da", "Vải", "Composite", "Sợi carbon"));
        CATALOG.put("MUSICAL_INSTRUMENTS", category("Nhạc cụ", "Gỗ", "Kim loại"));
        CATALOG.put("FINE_ART", category("Tranh & tượng", "Giấy", "Canvas", "Gỗ", "Kim loại", "Đá", "Kính/Thủy tinh", "Gốm/Sứ"));
        CATALOG.put("ELECTRONICS", category("Đồ điện & điện tử"));
        CATALOG.put("CAMERA_EQUIPMENT", category("Máy ảnh & máy quay"));
        CATALOG.put("OTHER", category("Hàng hóa khác"));
    }

    @Override
    public Integer getChecksum() {
        return 2026102703;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        List<Scope> scopes = readScopes(connection);
        Set<String> previousCategories = activeIds(connection, "goods_type_catalog");
        Set<String> previousMaterials = activeIds(connection, "goods_material_catalog");
        Map<String, String> materialAliases = materialAliases(connection);
        archiveCurrentCatalog(connection);

        int categoryOrder = 10;
        for (var entry : CATALOG.entrySet()) {
            int sortOrder = "OTHER".equals(entry.getKey()) ? Integer.MAX_VALUE : categoryOrder;
            byte[] categoryId = upsertCategory(connection, entry.getKey(), entry.getValue().label(), sortOrder);
            int materialOrder = 10;
            for (String material : entry.getValue().materials()) {
                upsertMaterial(connection, categoryId, material, materialOrder);
                materialOrder += 10;
            }
            categoryOrder += 10;
        }

        // Expand only scopes which previously accepted every active entry.
        // Restricted scopes keep their identities, including retired entries.
        ObjectMapper mapper = new ObjectMapper();
        for (Scope scope : scopes) {
            ObjectNode json = (ObjectNode) mapper.readTree(scope.json());
            Set<String> categories = ids(json, "categoryIds");
            Set<String> materials = ids(json, "materialIds");
            if (!categories.isEmpty() && !previousCategories.isEmpty() && categories.containsAll(previousCategories)) {
                json.set("categoryIds", mapper.valueToTree(activeIds(connection, "goods_type_catalog")));
            }
            if (!materials.isEmpty() && !previousMaterials.isEmpty() && materials.containsAll(previousMaterials)
                && (categories.isEmpty() || categories.containsAll(previousCategories))) {
                json.set("materialIds", mapper.valueToTree(activeIds(connection, "goods_material_catalog")));
            } else if (!materials.isEmpty()) {
                Set<String> mapped = new HashSet<>(materials);
                for (String oldId : materials) {
                    String alias = materialAliases.get(oldId);
                    if (alias == null) continue;
                    String[] parts = alias.split("\\|", 2);
                    byte[] categoryId = findCategoryId(connection, parts[0]);
                    byte[] materialId = categoryId == null ? null : findMaterialId(connection, categoryId, parts[1]);
                    if (materialId != null) mapped.add(uuidString(materialId));
                }
                json.set("materialIds", mapper.valueToTree(mapped));
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE unit_types SET accepted_goods_json = ? WHERE id = ?")) {
                statement.setString(1, mapper.writeValueAsString(json));
                statement.setBytes(2, scope.id());
                statement.executeUpdate();
            }
        }
    }

    private static List<Scope> readScopes(Connection connection) throws SQLException {
        List<Scope> result = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT id, accepted_goods_json FROM unit_types");
             var rows = statement.executeQuery()) {
            while (rows.next()) result.add(new Scope(rows.getBytes(1), rows.getString(2)));
        }
        return result;
    }

    private static Set<String> activeIds(Connection connection, String table) throws SQLException {
        Set<String> result = new HashSet<>();
        try (var statement = connection.prepareStatement("SELECT id FROM " + table + " WHERE status = 'ACTIVE'");
             var rows = statement.executeQuery()) {
            while (rows.next()) result.add(uuidString(rows.getBytes(1)));
        }
        return result;
    }

    private static Set<String> ids(ObjectNode json, String key) {
        Set<String> result = new HashSet<>();
        json.path(key).forEach(value -> result.add(value.asText().toLowerCase(java.util.Locale.ROOT)));
        return result;
    }

    private static Map<String, String> materialAliases(Connection connection) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement(
            "SELECT m.id, t.code, m.display_name FROM goods_material_catalog m JOIN goods_type_catalog t ON t.id = m.goods_type_id");
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                String label = rows.getString(3);
                String replacement = switch (label) {
                    case "Kính", "Thủy tinh", "Gương", "Kính/Thủy tinh" -> "Kính/Thủy tinh";
                    case "Gốm", "Sứ", "Gốm/Sứ" -> "Gốm/Sứ";
                    default -> label;
                };
                Category category = CATALOG.get(rows.getString(2));
                if (category != null && category.materials().contains(replacement)) {
                    result.put(uuidString(rows.getBytes(1)), rows.getString(2) + "|" + replacement);
                }
            }
        }
        return result;
    }

    private static String uuidString(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    private record Scope(byte[] id, String json) {}

    private static void archiveCurrentCatalog(Connection connection) throws SQLException {
        try (PreparedStatement categories = connection.prepareStatement(
            "UPDATE goods_type_catalog SET status = 'ARCHIVED'")) {
            categories.executeUpdate();
        }
        try (PreparedStatement materials = connection.prepareStatement(
            "UPDATE goods_material_catalog SET status = 'ARCHIVED'")) {
            materials.executeUpdate();
        }
    }

    private static byte[] upsertCategory(
        Connection connection, String code, String label, int sortOrder
    ) throws SQLException {
        byte[] id = findCategoryId(connection, code);
        if (id == null) {
            id = uuidBytes(UUID.randomUUID());
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO goods_type_catalog "
                    + "(id, code, display_name, status, sort_order, version, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 'ACTIVE', ?, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))")) {
                statement.setBytes(1, id);
                statement.setString(2, code);
                statement.setString(3, label);
                statement.setInt(4, sortOrder);
                statement.executeUpdate();
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE goods_type_catalog SET display_name = ?, status = 'ACTIVE', sort_order = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?")) {
                statement.setString(1, label);
                statement.setInt(2, sortOrder);
                statement.setBytes(3, id);
                statement.executeUpdate();
            }
        }
        return id;
    }

    private static void upsertMaterial(
        Connection connection, byte[] categoryId, String label, int sortOrder
    ) throws SQLException {
        byte[] id = findMaterialId(connection, categoryId, label);
        if (id == null) {
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO goods_material_catalog "
                    + "(id, goods_type_id, code, display_name, fragile, status, sort_order, version, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, FALSE, 'ACTIVE', ?, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))")) {
                statement.setBytes(1, uuidBytes(UUID.randomUUID()));
                statement.setBytes(2, categoryId);
                statement.setString(3, "MAT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
                statement.setString(4, label);
                statement.setInt(5, sortOrder);
                statement.executeUpdate();
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE goods_material_catalog SET fragile = FALSE, status = 'ACTIVE', sort_order = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?")) {
                statement.setInt(1, sortOrder);
                statement.setBytes(2, id);
                statement.executeUpdate();
            }
        }
    }

    private static byte[] findCategoryId(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM goods_type_catalog WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getBytes(1) : null;
            }
        }
    }

    private static byte[] findMaterialId(Connection connection, byte[] categoryId, String label) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM goods_material_catalog WHERE goods_type_id = ? AND display_name = ? ORDER BY created_at LIMIT 1")) {
            statement.setBytes(1, categoryId);
            statement.setString(2, label);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getBytes(1) : null;
            }
        }
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static Category category(String label, String... materials) {
        return new Category(label, List.of(materials));
    }

    private record Category(String label, List<String> materials) {}
}
