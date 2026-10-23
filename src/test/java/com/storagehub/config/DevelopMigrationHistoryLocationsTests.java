package com.storagehub.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class DevelopMigrationHistoryLocationsTests {
    @Test void renumberedReservationMigrationsKeepAppliedVersionsAndSelectDevelopForNewDatabases() throws Exception {
        for (boolean legacy : new boolean[]{true, false}) {
            var datasource=mock(DataSource.class);var connection=mock(Connection.class);var query=mock(PreparedStatement.class);
            var exists=mock(ResultSet.class);var statement=mock(Statement.class);var rows=mock(ResultSet.class);
            when(datasource.getConnection()).thenReturn(connection);when(connection.prepareStatement(anyString())).thenReturn(query);
            when(query.executeQuery()).thenReturn(exists);when(exists.next()).thenReturn(true);when(exists.getInt(1)).thenReturn(1);
            when(connection.createStatement()).thenReturn(statement);when(statement.executeQuery(anyString())).thenReturn(rows);
            when(rows.next()).thenReturn(legacy, legacy, false);
            when(rows.getString("version")).thenReturn("2026101900", "2026101904");
            when(rows.getString("script")).thenReturn("V2026101900__add_reservation_customer_contact_snapshot.sql", "V2026101904__add_reservation_code_sequence.sql");
            when(rows.getString("type")).thenReturn("SQL");when(rows.getBoolean("success")).thenReturn(true);
            var config=Flyway.configure().dataSource(datasource).locations("classpath:db/migration");
            DevelopMigrationHistoryLocations.configure(config);
            var locations=assertThat(config.getLocations()).extracting(Object::toString);
            if (legacy) locations.contains("classpath:db/migration-history/legacy-contact-1900", "classpath:db/migration-history/legacy-sequence-1904")
                .doesNotContain("classpath:db/migration-history/develop-contact-2002", "classpath:db/migration-history/develop-sequence-2003");
            else locations.contains("classpath:db/migration-history/develop-contact-2002", "classpath:db/migration-history/develop-sequence-2003")
                .doesNotContain("classpath:db/migration-history/legacy-contact-1900", "classpath:db/migration-history/legacy-sequence-1904");
            verify(statement,never()).execute(anyString());verify(statement,never()).executeUpdate(anyString());
        }
    }
    @Test void paymentOnlyLocationDoesNotInspectOrAlterDatasource(){
        var datasource=mock(DataSource.class);var config=Flyway.configure().dataSource(datasource).locations("classpath:db/paymentmigration");
        DevelopMigrationHistoryLocations.configure(config);
        assertThat(config.getLocations()).extracting(Object::toString).containsExactly("classpath:db/paymentmigration");verifyNoInteractions(datasource);
    }
    @Test void legacyReservationSequenceAt1901UsesItsChecksumAndAUniqueRenewalBridge() throws Exception {
        var datasource=mock(DataSource.class);var connection=mock(Connection.class);var query=mock(PreparedStatement.class);
        var exists=mock(ResultSet.class);var statement=mock(Statement.class);var rows=mock(ResultSet.class);
        when(datasource.getConnection()).thenReturn(connection);when(connection.prepareStatement(anyString())).thenReturn(query);
        when(query.executeQuery()).thenReturn(exists);when(exists.next()).thenReturn(true);when(exists.getInt(1)).thenReturn(1);
        when(connection.createStatement()).thenReturn(statement);when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true,false);when(rows.getString("version")).thenReturn("2026101901");
        when(rows.getString("script")).thenReturn("V2026101901__add_reservation_code_sequence.sql");
        when(rows.getString("type")).thenReturn("SQL");when(rows.getBoolean("success")).thenReturn(true);
        var config=Flyway.configure().dataSource(datasource).locations("classpath:db/migration");
        DevelopMigrationHistoryLocations.configure(config);
        assertThat(config.getLocations()).extracting(Object::toString)
            .contains("classpath:db/migration-history/legacy-sequence-1901", "classpath:db/migration-history/bridge-renewal-1901")
            .doesNotContain("classpath:db/migration-history/develop-renewal-1901", "classpath:db/migration-history/develop-sequence-2003");
    }
    @Test void unknownScriptAndFailedHistoryStopWithoutDdl() throws Exception {
        for(boolean success:new boolean[]{true,false}){
            var datasource=mock(DataSource.class);var connection=mock(Connection.class);var query=mock(PreparedStatement.class);
            var exists=mock(ResultSet.class);var statement=mock(Statement.class);var rows=mock(ResultSet.class);
            when(datasource.getConnection()).thenReturn(connection);when(connection.prepareStatement(anyString())).thenReturn(query);
            when(query.executeQuery()).thenReturn(exists);when(exists.next()).thenReturn(true);when(exists.getInt(1)).thenReturn(1);
            when(connection.createStatement()).thenReturn(statement);when(statement.executeQuery(anyString())).thenReturn(rows);
            when(rows.next()).thenReturn(true,false);when(rows.getString("version")).thenReturn("2026101001");
            when(rows.getString("script")).thenReturn(success?"unexpected.sql":"V2026101001__create_rental_ledger_and_notification_outbox.sql");
            when(rows.getString("type")).thenReturn("SQL");when(rows.getBoolean("success")).thenReturn(success);
            var config=Flyway.configure().dataSource(datasource).locations("classpath:db/migration");
            assertThatThrownBy(()->DevelopMigrationHistoryLocations.configure(config)).hasMessageContaining("Unrecognized or failed");
            verify(statement,never()).execute(anyString());verify(statement,never()).executeUpdate(anyString());verify(connection).close();
        }
    }
    @Test void appointmentVersionDoesNotRunLegacyLedgerCallback(){
        var context=mock(org.flywaydb.core.api.callback.Context.class);var info=mock(org.flywaydb.core.api.MigrationInfo.class);
        when(context.getMigrationInfo()).thenReturn(info);when(info.getVersion()).thenReturn(org.flywaydb.core.api.MigrationVersion.fromVersion("2026101001"));
        when(info.getScript()).thenReturn("V2026101001__add_customer_checkin_appointment.sql");
        assertThat(new LedgerMigrationSafetyCallback().supports(org.flywaydb.core.api.callback.Event.BEFORE_EACH_MIGRATE,context)).isFalse();
    }
}
