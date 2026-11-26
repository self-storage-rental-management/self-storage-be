package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2026112509__handover_arrival extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("""
                ALTER TABLE reservations ADD COLUMN handover_arrived_at DATETIME(6),
                    ADD COLUMN identity_supplement_requested_at DATETIME(6)
                """);
        }
    }
}
