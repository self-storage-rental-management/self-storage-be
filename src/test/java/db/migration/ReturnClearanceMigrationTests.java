package db.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

class ReturnClearanceMigrationTests {
    @Test
    void existingRecordsKeepTheirOutcomeAndBeginWithoutVerification() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE return_cases (status VARCHAR(40), refund INT)");
            statement.execute("INSERT INTO return_cases VALUES ('requested', NULL), ('completed', 17000)");
            var context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            new V2026112507__return_clearance().migrate(context);
            try (var rows = statement.executeQuery("SELECT * FROM return_cases ORDER BY status")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("status")).isEqualTo("completed");
                assertThat(rows.getInt("refund")).isEqualTo(17000);
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject("warehouse_empty_verified")).isNull();
                assertThat(rows.getObject("customer_cleared_at")).isNull();
                assertThat(rows.getLong("clearance_revision")).isZero();
            }
        }
    }
}
