package com.storagehub.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.Properties;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Profile;

class RentalSupportSchemaRolloutTests {
    DataSource datasource;Connection connection;Statement statement;ResultSet result;DatabaseMetaData metadata;
    @BeforeEach void setup() throws Exception {
        datasource=mock(DataSource.class);connection=mock(Connection.class);statement=mock(Statement.class);
        result=mock(ResultSet.class);metadata=mock(DatabaseMetaData.class);
        when(datasource.getConnection()).thenReturn(connection);when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("select version(), database()")).thenReturn(result);
        when(result.next()).thenReturn(true);when(result.getString(1)).thenReturn("8.4.11");
        when(result.getString(2)).thenReturn("storagehub_test");when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
    }
    @Test void verifiedMySqlAndExactTargetPassWithoutDdl() throws Exception {
        RentalSupportSchemaRolloutConfiguration.verifyTarget(datasource,"storagehub_test");
        verify(statement).executeQuery("select version(), database()");verify(statement,never()).execute(anyString());
        verify(connection).close();verify(statement).close();verify(result).close();
    }
    @Test void undeclaredTargetFailsBeforeOpeningConnection() {
        for(var name:new String[]{"","with spaces","wrong;drop"})
            assertThatThrownBy(()->RentalSupportSchemaRolloutConfiguration.verifyTarget(datasource,name)).hasMessageContaining("explicit expected");
        verifyNoInteractions(datasource);
    }
    @Test void wrongDatabaseStopsInsteadOfMigratingAnotherDatabase() throws Exception {
        when(result.getString(2)).thenReturn("another_database");
        assertThatThrownBy(()->RentalSupportSchemaRolloutConfiguration.verifyTarget(datasource,"storagehub_test"))
            .hasMessageContaining("does not match");
    }
    @Test void mysqlProtocolDoesNotMakeTiDbASupportedTarget() throws Exception {
        when(result.getString(1)).thenReturn("5.7.25-TiDB-v8.5.0");
        assertThatThrownBy(()->RentalSupportSchemaRolloutConfiguration.verifyTarget(datasource,"storagehub_test"))
            .hasMessageContaining("not verified MySQL 8.4");
    }
    @Test void untestedMySqlVersionAndOtherProductStop() throws Exception {
        when(result.getString(1)).thenReturn("8.0.42");
        assertThatThrownBy(()->RentalSupportSchemaRolloutConfiguration.verifyTarget(datasource,"storagehub_test")).hasMessageContaining("not verified");
        when(result.getString(1)).thenReturn("8.4.11");when(metadata.getDatabaseProductName()).thenReturn("MariaDB");
        assertThatThrownBy(()->RentalSupportSchemaRolloutConfiguration.verifyTarget(datasource,"storagehub_test")).hasMessageContaining("not verified");
    }
    @Test void missingConnectionIdentityStops() throws Exception {
        when(result.next()).thenReturn(false);
        assertThatThrownBy(()->RentalSupportSchemaRolloutConfiguration.verifyTarget(datasource,"storagehub_test")).hasMessageContaining("cannot verify");
    }
    @Test void profileIsOptInAndDoesNotEnableFeaturesOrUnsafeSchemaModes() throws Exception {
        assertThat(RentalSupportSchemaRolloutConfiguration.class.getAnnotation(Profile.class).value()).containsExactly("rental-support-schema");
        var p=new Properties();try(var stream=getClass().getResourceAsStream("/application-rental-support-schema.properties")){p.load(stream);}
        assertThat(p.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration,classpath:db/paymentmigration");
        assertThat(p.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        for(var key:new String[]{"spring.flyway.baseline-on-migrate","spring.flyway.out-of-order","app.bootstrap-demo-data.enabled",
            "storagehub.integration.ledger.enabled","storagehub.integration.communication.enabled"})assertThat(p.getProperty(key)).isEqualTo("false");
        assertThat(p.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
        assertThat(p.getProperty("spring.datasource.url")).isEqualTo("${STORAGEHUB_DB_URL}");
    }
}
