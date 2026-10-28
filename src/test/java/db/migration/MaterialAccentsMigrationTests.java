package db.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

class MaterialAccentsMigrationTests {
    @Test
    void restoresStoneWithoutChangingLeatherOrRestrictedScopesAndIsIdempotent() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL")) {
            UUID furniture = UUID.randomUUID(), leather = UUID.randomUUID();
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE goods_type_catalog (id BINARY(16), code VARCHAR(40), status VARCHAR(16))");
                statement.execute("CREATE TABLE goods_material_catalog (id BINARY(16), goods_type_id BINARY(16), code VARCHAR(24), display_name VARCHAR(160), fragile BOOLEAN, status VARCHAR(16), sort_order INT, version BIGINT, created_at TIMESTAMP, updated_at TIMESTAMP)");
                statement.execute("CREATE TABLE unit_types (id BINARY(16), accepted_goods_json VARCHAR(10000))");
            }
            try (var statement = connection.prepareStatement("INSERT INTO goods_type_catalog VALUES (?, 'FURNITURE', 'ACTIVE')")) {
                statement.setBytes(1, bytes(furniture)); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("INSERT INTO goods_material_catalog VALUES (?, ?, 'LEATHER', 'Da', FALSE, 'ACTIVE', 10, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                statement.setBytes(1, bytes(leather)); statement.setBytes(2, bytes(furniture)); statement.executeUpdate();
            }
            ObjectMapper mapper = new ObjectMapper();
            String full = mapper.writeValueAsString(Map.of("categoryIds", List.of(furniture), "materialIds", List.of(leather)));
            String restricted = mapper.writeValueAsString(Map.of("categoryIds", List.of(UUID.randomUUID()), "materialIds", List.of(leather)));
            try (var statement = connection.prepareStatement("INSERT INTO unit_types VALUES (?, ?)")) {
                statement.setBytes(1, bytes(new UUID(0, 1))); statement.setString(2, full); statement.executeUpdate();
                statement.setBytes(1, bytes(new UUID(0, 2))); statement.setString(2, restricted); statement.executeUpdate();
            }
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            var migration = new V2026102801__preserve_vietnamese_material_accents();
            migration.migrate(context);
            migration.migrate(context);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT display_name FROM goods_material_catalog")) {
                var names = new java.util.ArrayList<String>();
                while (rows.next()) names.add(rows.getString(1));
                assertThat(names).containsExactlyInAnyOrder("Da", "Đá");
            }
            try (var statement = connection.prepareStatement("SELECT accepted_goods_json FROM unit_types WHERE id = ?")) {
                statement.setBytes(1, bytes(new UUID(0, 1)));
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    assertThat(mapper.readTree(rows.getString(1)).path("materialIds").size()).isEqualTo(2);
                }
                statement.setBytes(1, bytes(new UUID(0, 2)));
                try (var rows = statement.executeQuery()) {
                    rows.next(); assertThat(rows.getString(1)).isEqualTo(restricted);
                }
            }
        }
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
