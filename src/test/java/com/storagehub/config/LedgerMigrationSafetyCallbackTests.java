package com.storagehub.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;

class LedgerMigrationSafetyCallbackTests {
    @Test void supportsOnlyBeforeTheLedgerVersionAndDoesNotHandleOtherMigrations() {
        var callback=new LedgerMigrationSafetyCallback();var context=mock(Context.class);var info=mock(MigrationInfo.class);
        when(context.getMigrationInfo()).thenReturn(info);when(info.getVersion()).thenReturn(MigrationVersion.fromVersion("2026101001"));
        assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE,context)).isTrue();
        assertThat(callback.supports(Event.AFTER_EACH_MIGRATE,context)).isFalse();
        when(info.getVersion()).thenReturn(MigrationVersion.fromVersion("2026101101"));
        callback.handle(Event.BEFORE_EACH_MIGRATE,context);verify(context,never()).getConnection();
        when(info.getVersion()).thenReturn(null);assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE,context)).isFalse();
        when(context.getMigrationInfo()).thenReturn(null);assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE,context)).isFalse();
    }
    @Test void unverifiedTiDbStopsBeforeLedgerDdlWithoutClosingFlywayConnection() throws Exception {
        var callback=new LedgerMigrationSafetyCallback();var context=mock(Context.class);var info=mock(MigrationInfo.class);
        when(context.getMigrationInfo()).thenReturn(info);when(info.getVersion()).thenReturn(MigrationVersion.fromVersion("2026101001"));
        var connection=mock(Connection.class);var statement=mock(Statement.class);var database=mock(ResultSet.class);var identity=mock(ResultSet.class);
        when(context.getConnection()).thenReturn(connection);when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("select database()")).thenReturn(database);when(database.next()).thenReturn(true,false);
        var columns=mock(ResultSetMetaData.class);when(database.getMetaData()).thenReturn(columns);when(columns.getColumnCount()).thenReturn(1);
        when(database.getString(1)).thenReturn("isolated_test");
        when(statement.executeQuery("select version(), database()")).thenReturn(identity);when(identity.next()).thenReturn(true);
        when(identity.getString(1)).thenReturn("5.7.25-TiDB-v8.5.0");when(identity.getString(2)).thenReturn("isolated_test");
        assertThatThrownBy(()->callback.handle(Event.BEFORE_EACH_MIGRATE,context)).hasMessageContaining("not verified MySQL 8.4");
        verify(connection,never()).close();verify(statement,never()).execute(anyString());
    }
}
