package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2026112507__return_clearance extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE return_cases ADD COLUMN ("
                + "customer_cleared_at DATETIME(6), warehouse_empty_verified BOOLEAN, "
                + "warehouse_empty_verified_at DATETIME(6), warehouse_empty_verified_by BINARY(16), "
                + "clearance_revision BIGINT NOT NULL DEFAULT 0)");
        }
    }
}
