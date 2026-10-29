package db.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

class GoodsCatalogReplacementTests {
    @Test
    void preservesRestrictedScopesAndMapsCombinedMaterials() throws Exception {
        verifyScope(false);
    }

    @Test
    void refreshesPreviouslyUnrestrictedScopesWithTheNewCatalog() throws Exception {
        verifyScope(true);
    }

    private void verifyScope(boolean unrestricted) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE goods_type_catalog (id BINARY(16) PRIMARY KEY, code VARCHAR(40), display_name VARCHAR(160), status VARCHAR(16), sort_order INT, version BIGINT, created_at TIMESTAMP, updated_at TIMESTAMP)");
                statement.execute("CREATE TABLE goods_material_catalog (id BINARY(16) PRIMARY KEY, goods_type_id BINARY(16), code VARCHAR(24), display_name VARCHAR(160), fragile BOOLEAN, status VARCHAR(16), sort_order INT, version BIGINT, created_at TIMESTAMP, updated_at TIMESTAMP)");
                statement.execute("CREATE TABLE unit_types (id BINARY(16) PRIMARY KEY, accepted_goods_json VARCHAR(10000))");
            }
            UUID furniture = UUID.randomUUID(), office = UUID.randomUUID();
            UUID wood = UUID.randomUUID(), glass = UUID.randomUUID(), paper = UUID.randomUUID(), unit = UUID.randomUUID();
            category(connection, furniture, "FURNITURE");
            category(connection, office, "OFFICE");
            material(connection, wood, furniture, "Gỗ");
            material(connection, glass, furniture, "Kính");
            material(connection, paper, office, "Giấy");
            ObjectMapper mapper = new ObjectMapper();
            String scope = mapper.writeValueAsString(Map.of(
                "categoryIds", unrestricted ? List.of(furniture, office) : List.of(furniture),
                "materialIds", unrestricted ? List.of(wood, glass, paper) : List.of(glass)));
            try (var statement = connection.prepareStatement("INSERT INTO unit_types VALUES (?, ?)")) {
                statement.setBytes(1, bytes(unit));
                statement.setString(2, scope);
                statement.executeUpdate();
            }
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            new V2026102703__replace_goods_catalog().migrate(context);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT accepted_goods_json FROM unit_types")) {
                rows.next();
                var json = mapper.readTree(rows.getString(1));
                if (unrestricted) {
                    assertThat(json.path("categoryIds").size()).isEqualTo(11);
                    assertThat(json.path("materialIds").size()).isGreaterThan(3);
                } else {
                    assertThat(json.path("categoryIds").size()).isEqualTo(1);
                    assertThat(json.path("categoryIds").get(0).asText()).isEqualTo(furniture.toString());
                    assertThat(json.path("materialIds").size()).isEqualTo(2);
                    assertThat(json.path("materialIds").toString()).doesNotContain(wood.toString());
                }
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT status FROM goods_material_catalog WHERE display_name = 'Kính'")) {
                rows.next();
                assertThat(rows.getString(1)).isEqualTo("ARCHIVED");
            }
        }
    }

    private void category(Connection connection, UUID id, String code) throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO goods_type_catalog VALUES (?, ?, ?, 'ACTIVE', 10, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
            statement.setBytes(1, bytes(id)); statement.setString(2, code); statement.setString(3, code); statement.executeUpdate();
        }
    }

    private void material(Connection connection, UUID id, UUID category, String label) throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO goods_material_catalog VALUES (?, ?, ?, ?, FALSE, 'ACTIVE', 10, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
            statement.setBytes(1, bytes(id)); statement.setBytes(2, bytes(category)); statement.setString(3, id.toString().substring(0, 24)); statement.setString(4, label); statement.executeUpdate();
        }
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
