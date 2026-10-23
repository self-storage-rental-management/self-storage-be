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
    private record AppliedHistory(String script,String type) {}
    private FluentConfiguration configure(String calendar,String gateway,String gatewayType) throws Exception {
        return configure(calendar,gateway,gatewayType,Map.of());
    }
    private FluentConfiguration configure(String calendar,String gateway,String gatewayType,Map<String,AppliedHistory> history) throws Exception {
        var ds=mock(DataSource.class);var connection=mock(Connection.class);when(ds.getConnection()).thenReturn(connection);
        var query=mock(PreparedStatement.class);when(connection.prepareStatement(anyString())).thenReturn(query);
        var count=mock(ResultSet.class);when(query.executeQuery()).thenReturn(count);when(count.next()).thenReturn(true,false);when(count.getInt(1)).thenReturn(1);
        var statement=mock(Statement.class);when(connection.createStatement()).thenReturn(statement);
        var rows=mock(ResultSet.class);when(statement.executeQuery(anyString())).thenReturn(rows);
        var versions=new ArrayList<String>();var scripts=new ArrayList<String>();var types=new ArrayList<String>();
        if(calendar!=null){versions.add("2026101301");scripts.add(calendar);types.add("SQL");}
        if(gateway!=null){versions.add("2026101302");scripts.add(gateway);types.add(gatewayType);}
        history.forEach((version,applied)->{versions.add(version);scripts.add(applied.script());types.add(applied.type());});
        var index=new int[]{-1};when(rows.next()).thenAnswer(i->++index[0]<versions.size());
        when(rows.getString("version")).thenAnswer(i->versions.get(index[0]));
        when(rows.getString("script")).thenAnswer(i->scripts.get(index[0]));
        when(rows.getString("type")).thenAnswer(i->types.get(index[0]));when(rows.getBoolean("success")).thenReturn(true);
        return DevelopMigrationHistoryLocations.configure(Flyway.configure().dataSource(ds).locations("classpath:db/migration","classpath:db/sandboxpaymentmigration","classpath:db/renewal-calendar-migration"));
    }
    private void uniqueVersions(FluentConfiguration config) {
        var flyway=config.dataSource("jdbc:h2:mem:routing"+UUID.randomUUID()+";MODE=MySQL","sa","").load();
        var versions=Arrays.stream(flyway.info().all()).filter(i->i.getVersion()!=null).map(i->i.getVersion().toString()).toList();
        assertThat(versions).doesNotHaveDuplicates().contains("2026101301","2026101302","2026101401","2026101402","2026101403","2026101404","2026101405","2026101406","2026101410","2026101411","2026101412","2026101413","2026101414","2026101415","2026101416");
    }
    @Test void freshDatabaseResolvesDevelopAndNewFeatureVersionsWithoutCollision() throws Exception {
        var config=configure(null,null,null);uniqueVersions(config);
        assertThat(config.isOutOfOrder()).isFalse();
        assertThat(Arrays.stream(config.getLocations()).map(Object::toString).toList())
            .contains("classpath:db/migration-history/develop-1501").doesNotContain("classpath:db/sandboxpaymentmigration");
        assertThat(Arrays.stream(config.dataSource("jdbc:h2:mem:deposit-routing"+UUID.randomUUID(),"sa","").load().info().all())
            .filter(m->m.getVersion()!=null).map(m->m.getVersion().toString()).toList()).contains("2026101501");
    }
    @Test void appliedDevelopVersionsRemainImmutable() throws Exception {
        uniqueVersions(configure("V2026101301__deactivate_duplicate_effective_rental_packages.sql","V2026101302__add_facility_operating_details.sql","SQL"));
    }
    @Test void legacyReservationSequenceAt1901UsesAUniqueRenewalBridge() throws Exception {
        var history=Map.of("2026101901",new AppliedHistory("V2026101901__add_reservation_code_sequence.sql","SQL"));
        var config=configure(null,null,null,history);
        uniqueVersions(config);
        assertThat(Arrays.stream(config.getLocations()).map(Object::toString).toList())
            .contains("classpath:db/migration-history/legacy-sequence-1901", "classpath:db/migration-history/bridge-renewal-1901")
            .doesNotContain("classpath:db/migration-history/develop-renewal-1901");
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
    @Test void appliedLocalJavaVersions1401Through1406KeepTheirOriginalIdentity() throws Exception {
        var history=new LinkedHashMap<String,AppliedHistory>();
        history.put("2026101401",new AppliedHistory("db.migration.V2026101401__reconcile_develop_package_cleanup","JDBC"));
        history.put("2026101402",new AppliedHistory("db.migration.V2026101402__reconcile_develop_facility_details","JDBC"));
        history.put("2026101403",new AppliedHistory("db.renewalcalendarmigration.V2026101403__ensure_staff_signing_calendar","JDBC"));
        history.put("2026101404",new AppliedHistory("db.renewalpaymentmigration.V2026101404__ensure_gateway_ledger_evidence","JDBC"));
        history.put("2026101405",new AppliedHistory("db.migration.V2026101405__reconcile_develop_package_cleanup","JDBC"));
        history.put("2026101406",new AppliedHistory("db.migration.V2026101406__reconcile_develop_facility_details","JDBC"));
        var config=configure(null,null,null,history);
        uniqueVersions(config);
        var resolved=Arrays.stream(config.getJavaMigrations())
            .collect(java.util.stream.Collectors.toMap(m->m.getVersion().toString(),m->m.getResolvedMigration(config,null).getScript()));
        history.forEach((version,applied)->assertThat(resolved).containsEntry(version,applied.script()));
    }
    @Test void appliedDevelopSqlVersions1404Through1406StayResolvedAsSql() throws Exception {
        var history=Map.of(
            "2026101404",new AppliedHistory("V2026101404__support_image_change_audit_snapshots.sql","SQL"),
            "2026101405",new AppliedHistory("V2026101405__separate_unit_type_detail_image.sql","SQL"),
            "2026101406",new AppliedHistory("V2026101406__persist_facility_content_policies.sql","SQL"));
        var config=configure(null,null,null,history);
        uniqueVersions(config);
        assertThat(Arrays.stream(config.getJavaMigrations()).map(m->m.getVersion().toString()).toList())
            .doesNotContain("2026101404","2026101405","2026101406");
    }
}
