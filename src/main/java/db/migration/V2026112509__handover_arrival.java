package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Records when the customer physically arrived for handover. The visit backfill
 * migration V2026112510 already introduces {@code handover_arrived_at} whenever it
 * runs first, so each column is added only when it is still missing.
 */
public class V2026112509__handover_arrival extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        addColumnIfMissing(connection, "handover_arrived_at",
            "ALTER TABLE reservations ADD COLUMN handover_arrived_at DATETIME(6) NULL");
        addColumnIfMissing(connection, "identity_supplement_requested_at",
            "ALTER TABLE reservations ADD COLUMN identity_supplement_requested_at DATETIME(6) NULL");
    }

    private static void addColumnIfMissing(Connection connection, String column, String ddl) throws SQLException {
        if (hasColumn(connection, "reservations", column)) {
            return;
        }
        try (var statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        String catalog = connection.getCatalog();
        for (String candidate : new String[] {column, column.toUpperCase()}) {
            try (var rows = connection.getMetaData().getColumns(catalog, null, table, candidate)) {
                if (rows.next()) {
                    return true;
                }
            }
        }
        return false;
    }
}
