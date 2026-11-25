package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2026112508__check_in_declared_identity extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("""
                CREATE TABLE check_in_identity_display (
                    id BINARY(16) NOT NULL PRIMARY KEY,
                    declared_identity_masked VARCHAR(12),
                    identity_corrected BOOLEAN
                ) ENGINE=InnoDB
                """);
            statement.executeUpdate("""
                INSERT INTO check_in_identity_display (id, declared_identity_masked, identity_corrected)
                SELECT c.id,
                    CASE WHEN first_correction.ending REGEXP '^[0-9]{4}$'
                        THEN CONCAT('********', first_correction.ending) END,
                    TRUE
                FROM check_ins c
                JOIN (
                    SELECT entity_id, JSON_UNQUOTE(JSON_EXTRACT(after_state_json, '$.previousEnding')) AS ending,
                        ROW_NUMBER() OVER (PARTITION BY entity_id ORDER BY created_at, id) AS position_in_history
                    FROM activity_logs
                    WHERE entity_type = 'CheckIn' AND action = 'CHECKIN_IDENTITY_CORRECTED'
                        AND JSON_VALID(after_state_json)
                ) first_correction
                    ON first_correction.entity_id = c.id AND first_correction.position_in_history = 1
                """);
        }
    }
}
