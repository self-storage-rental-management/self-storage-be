package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Keeps databases created by the older settings schema compatible with the
 * current SystemSetting mapping, which stores values in setting_value.
 */
public class V2026102701__align_system_settings_legacy_value extends BaseJavaMigration {
    @Override
    public Integer getChecksum() {
        return 2026102701;
    }

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!hasColumn(connection, "value")) {
            return;
        }
        if (!hasColumn(connection, "setting_value")) {
            throw new SQLException("system_settings has legacy value but is missing setting_value");
        }

        try (var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE system_settings SET value = setting_value WHERE value IS NULL");
            statement.execute("ALTER TABLE system_settings MODIFY COLUMN value VARCHAR(2000) NULL");
        }
    }

    private static boolean hasColumn(Connection connection, String column) throws SQLException {
        try (var query = connection.prepareStatement(
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'system_settings' AND column_name = ?")) {
            query.setString(1, column);
            try (var rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1) == 1;
            }
        }
    }
}
