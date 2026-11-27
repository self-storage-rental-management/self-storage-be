package db.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

class HandoverArrivalMigrationTests {
    @Test
    void addsBothArrivalColumnsWhenNeitherExists() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE reservations (id BINARY(16) NOT NULL PRIMARY KEY)");
            }
            var context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);

            new V2026112509__handover_arrival().migrate(context);

            assertThat(hasColumn(connection, "handover_arrived_at")).isTrue();
            assertThat(hasColumn(connection, "identity_supplement_requested_at")).isTrue();
        }
    }

    @Test
    void toleratesArrivalColumnAlreadyAddedByVisitBackfill() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE reservations (id BINARY(16) NOT NULL PRIMARY KEY,"
                    + " handover_arrived_at DATETIME(6) NULL)");
            }
            var context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);

            var migration = new V2026112509__handover_arrival();
            migration.migrate(context);
            migration.migrate(context);

            assertThat(hasColumn(connection, "handover_arrived_at")).isTrue();
            assertThat(hasColumn(connection, "identity_supplement_requested_at")).isTrue();
            try (var rows = connection.createStatement().executeQuery("SELECT * FROM reservations")) {
                assertThat(rows.getMetaData().getColumnCount()).isEqualTo(3);
            }
        }
    }

    @Test
    void keepsExistingArrivalValuesWhenSupplementColumnIsMissing() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE reservations (id BINARY(16) NOT NULL PRIMARY KEY,"
                    + " handover_arrived_at DATETIME(6) NULL)");
                statement.execute("INSERT INTO reservations (id, handover_arrived_at)"
                    + " VALUES (X'00000000000000000000000000000001', TIMESTAMP '2026-11-25 08:00:00')");
            }
            var context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);

            new V2026112509__handover_arrival().migrate(context);

            try (var rows = connection.createStatement().executeQuery(
                    "SELECT handover_arrived_at FROM reservations")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getTimestamp("handover_arrived_at")).isNotNull();
                assertThat(rows.getTimestamp("handover_arrived_at").toLocalDateTime().toString())
                    .isEqualTo("2026-11-25T08:00");
            }
        }
    }

    private static boolean hasColumn(java.sql.Connection connection, String column) throws Exception {
        try (var rows = connection.getMetaData().getColumns(null, null, "reservations", column)) {
            return rows.next();
        }
    }
}
