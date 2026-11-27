package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V2026112506__reservation_handover_attendance extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("""
                ALTER TABLE reservations
                    ADD COLUMN handover_terms_version VARCHAR(40) NULL,
                    ADD COLUMN identity_hold_reason VARCHAR(1000) NULL,
                    ADD COLUMN identity_documents_received_at DATETIME(6) NULL,
                    ADD COLUMN identity_review_approved_at DATETIME(6) NULL,
                    ADD COLUMN attendance_reminder_hours INT NOT NULL DEFAULT 0,
                    ADD INDEX idx_res_handover_due (handover_terms_version, status, appointment_at)
                """);
        }
    }
}
