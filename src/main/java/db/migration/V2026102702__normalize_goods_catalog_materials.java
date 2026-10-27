package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Replaces catalog labels that describe a component or finished object with
 * actual material names, while retaining archived rows for historical data.
 */
public class V2026102702__normalize_goods_catalog_materials extends BaseJavaMigration {
    @Override
    public Integer getChecksum() {
        return 2026102702;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        archive(connection, "FURNITURE", "Gương");

        rename(connection, "ELECTRONICS", "Kính màn hình", "Kính", true);
        archive(connection, "ELECTRONICS", "Màn hình LCD/LED");

        rename(connection, "TOYS", "Kính hoặc chi tiết thủy tinh nếu có", "Kính", true);
        rename(connection, "SPORTS", "Vật liệu carbon mỏng cần bảo vệ đặc biệt", "Sợi carbon", true);
        rename(connection, "MUSICAL_INSTRUMENTS", "Bộ phận kính hoặc vật liệu mỏng, dễ nứt", "Thủy tinh", true);

        rename(connection, "CAMERA_EQUIPMENT", "Kính quang học", "Kính", true);
        rename(connection, "CAMERA_EQUIPMENT", "Ống kính", "Thủy tinh", true);
        rename(connection, "EVENT_EQUIPMENT", "Mica cứng dễ nứt", "Mica", true);

        rename(connection, "STORE_FIXTURES", "Kính", "Thủy tinh", true);
        rename(connection, "STORE_FIXTURES", "Gương", "Kính", true);

        rename(connection, "FINE_ART", "Kính khung tranh", "Kính", true);
        rename(connection, "FINE_ART", "Tượng dễ vỡ", "Sứ", true);
        rename(connection, "MOVING_ITEMS", "Gương", "Thủy tinh", true);
    }

    private static void rename(
        Connection connection,
        String categoryCode,
        String oldLabel,
        String newLabel,
        boolean fragile
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE goods_material_catalog material "
                + "JOIN goods_type_catalog type ON type.id = material.goods_type_id "
                + "SET material.display_name = ?, material.fragile = ?, material.status = 'ACTIVE' "
                + "WHERE type.code = ? AND material.display_name = ?")) {
            statement.setString(1, newLabel);
            statement.setBoolean(2, fragile);
            statement.setString(3, categoryCode);
            statement.setString(4, oldLabel);
            statement.executeUpdate();
        }
    }

    private static void archive(Connection connection, String categoryCode, String label) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE goods_material_catalog material "
                + "JOIN goods_type_catalog type ON type.id = material.goods_type_id "
                + "SET material.status = 'ARCHIVED' "
                + "WHERE type.code = ? AND material.display_name = ?")) {
            statement.setString(1, categoryCode);
            statement.setString(2, label);
            statement.executeUpdate();
        }
    }
}
