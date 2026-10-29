package db.migration;

import java.sql.Types;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Keep long notes off the MySQL row before later return workflow columns are added. */
public class V2026102609_1__move_return_notes_off_row extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        for (String column : List.of("customer_notes", "inspection_notes", "evidence_photos_json",
            "legacy_pricing_decision_reason", "customer_decision_note", "manager_resolution_note")) {
            try (var metadata = connection.getMetaData().getColumns(connection.getCatalog(), null, "return_cases", column)) {
                if (!metadata.next() || metadata.getInt("DATA_TYPE") != Types.VARCHAR) continue;
            }
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE return_cases MODIFY COLUMN " + column + " TEXT NULL");
            }
        }
    }
}
