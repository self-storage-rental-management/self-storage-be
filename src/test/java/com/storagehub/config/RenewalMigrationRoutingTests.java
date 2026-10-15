package com.storagehub.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

class RenewalMigrationRoutingTests {
    private FluentConfiguration configure(String calendar,String gateway,String gatewayType) throws Exception {
        var ds=mock(DataSource.class);var connection=mock(Connection.class);when(ds.getConnection()).thenReturn(connection);
        var query=mock(PreparedStatement.class);when(connection.prepareStatement(anyString())).thenReturn(query);
        var count=mock(ResultSet.class);when(query.executeQuery()).thenReturn(count);when(count.next()).thenReturn(true,false);when(count.getInt(1)).thenReturn(1);
        var statement=mock(Statement.class);when(connection.createStatement()).thenReturn(statement);
        var rows=mock(ResultSet.class);when(statement.executeQuery(anyString())).thenReturn(rows);
        var versions=new ArrayList<String>();var scripts=new ArrayList<String>();var types=new ArrayList<String>();
        if(calendar!=null){versions.add("2026101301");scripts.add(calendar);types.add("SQL");}
        if(gateway!=null){versions.add("2026101302");scripts.add(gateway);types.add(gatewayType);}
        var index=new int[]{-1};when(rows.next()).thenAnswer(i->++index[0]<versions.size());
        when(rows.getString("version")).thenAnswer(i->versions.get(index[0]));
        when(rows.getString("script")).thenAnswer(i->scripts.get(index[0]));
        when(rows.getString("type")).thenAnswer(i->types.get(index[0]));when(rows.getBoolean("success")).thenReturn(true);
        return DevelopMigrationHistoryLocations.configure(Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/sandboxpaymentmigration","classpath:db/renewal-calendar-migration"));
    }
    private void uniqueVersions(FluentConfiguration config) {
        var flyway=config.dataSource("jdbc:h2:mem:routing"+UUID.randomUUID()+";MODE=MySQL","sa","").load();
        var versions=Arrays.stream(flyway.info().all()).filter(i->i.getVersion()!=null).map(i->i.getVersion().toString()).toList();
        assertThat(versions).doesNotHaveDuplicates().contains("2026101301","2026101302","2026101401","2026101402","2026101403","2026101410","2026101411");
    }
    @Test void freshDatabaseResolvesDevelopAndNewFeatureVersionsWithoutCollision() throws Exception {
        var config=configure(null,null,null);uniqueVersions(config);
        assertThat(config.isOutOfOrder()).isFalse();
    }
    @Test void appliedDevelopVersionsRemainImmutable() throws Exception {
        uniqueVersions(configure("V2026101301__deactivate_duplicate_effective_rental_packages.sql","V2026101302__add_facility_operating_details.sql","SQL"));
    }
    @Test void legacyCalendarAndGatewayRetainOriginalVersionsAndAllowPendingDevelopMigrations() throws Exception {
        var config=configure("V2026101301__create_staff_renewal_signing_calendar.sql","db.sandboxpaymentmigration.V2026101302__ensure_gateway_ledger_evidence","JDBC");
        assertThat(config.isOutOfOrder()).isTrue();uniqueVersions(config);
        assertThat(Arrays.stream(config.getJavaMigrations()).map(m->m.getClass().getName()).toList())
            .contains("db.sandboxpaymentmigration.V2026101302__ensure_gateway_ledger_evidence");
    }
    @Test void unknownHistoricalVersionStopsWithoutRepair() throws Exception {
        assertThatThrownBy(()->configure(null,"unexpected.sql","SQL")).hasMessageContaining("Unrecognized");
    }
}
