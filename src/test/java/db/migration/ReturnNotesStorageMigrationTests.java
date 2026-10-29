package db.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

class ReturnNotesStorageMigrationTests {
    @Test
    void preservesLongNotesWhenMovingOffRowAndCanRunAgain() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            List<String> columns = List.of("customer_notes", "inspection_notes", "evidence_photos_json",
                "legacy_pricing_decision_reason", "customer_decision_note", "manager_resolution_note");
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE return_cases (" + String.join(",", columns.stream().map(column -> column + " VARCHAR(12000)").toList()) + ")");
            }
            String original = "Ghi chú gốc Đá và Da. ".repeat(150);
            try (var statement = connection.prepareStatement("INSERT INTO return_cases VALUES (?, ?, ?, ?, ?, ?)")) {
                for (int i = 1; i <= columns.size(); i++) statement.setString(i, original);
                statement.executeUpdate();
            }
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            var migration = new V2026102609_1__move_return_notes_off_row();
            migration.migrate(context);
            migration.migrate(context);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM return_cases")) {
                assertThat(rows.next()).isTrue();
                for (String column : columns) assertThat(rows.getString(column)).isEqualTo(original);
            }
        }
    }
}
