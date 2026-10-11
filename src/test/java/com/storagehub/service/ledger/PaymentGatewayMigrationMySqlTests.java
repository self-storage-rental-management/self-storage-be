package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

/** Each case creates a separate test schema. Never cleans, drops, or uses a shared schema. */
@EnabledIfEnvironmentVariable(named="STORAGEHUB_SCHEMA_TEST_URL", matches=".+")
class PaymentGatewayMigrationMySqlTests {
    HikariDataSource datasource;
    JdbcTemplate jdbc;
    Flyway flyway;
    @BeforeEach void freshSchema() {
        String url=System.getenv("STORAGEHUB_SCHEMA_TEST_URL");
        assertThat(url).matches("^jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/storagehub_schema_test_[a-z0-9_]+(?:\\?.*)?$");
        String database="storagehub_schema_test_payment_"+UUID.randomUUID().toString().replace("-","");
        try(var admin=new HikariDataSource()) {
            admin.setJdbcUrl(url);admin.setUsername(System.getenv("STORAGEHUB_SCHEMA_TEST_USERNAME"));
            admin.setPassword(System.getenv("STORAGEHUB_SCHEMA_TEST_PASSWORD"));
            new JdbcTemplate(admin).execute("create database "+database);
        }
        String testUrl=url.replaceFirst("/storagehub_schema_test_[a-z0-9_]+", "/"+database);
        datasource=new HikariDataSource();datasource.setJdbcUrl(testUrl);
        datasource.setUsername(System.getenv("STORAGEHUB_SCHEMA_TEST_USERNAME"));
        datasource.setPassword(System.getenv("STORAGEHUB_SCHEMA_TEST_PASSWORD"));datasource.setMaximumPoolSize(2);
        jdbc=new JdbcTemplate(datasource);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database()",Long.class)).isZero();
        jdbc.execute("create table payments (id binary(16) primary key, amount decimal(14,2) not null, currency varchar(3) not null, status varchar(32) not null, idempotency_key varchar(100) unique not null, processed_at timestamp(6) null, paid_at timestamp(6) null, version bigint not null, updated_at timestamp(6) not null)");
        // Explicit baseline is fixture-only: the table above models an old Payment schema, not a team DB.
        flyway=Flyway.configure().dataSource(datasource).locations("classpath:db/paymentmigration")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        System.out.println("Payment migration isolated fixture: "+database);
    }
    @AfterEach void close() { if(datasource!=null)datasource.close(); }
    String payment() {
        String id=UUID.randomUUID().toString().replace("-","");
        jdbc.update("insert into payments values(unhex(?),12345.00,'VND','PAID',?,'2026-10-10 10:11:12.123456','2026-10-10 10:11:13.123456',4,'2026-10-10 10:11:14.123456')",id,"TEST-"+id);
        return id;
    }
    Map<String,Object> businessFields(String id) {
        return jdbc.queryForMap("select hex(id) as id,amount,currency,status,idempotency_key,processed_at,paid_at,version,updated_at from payments where id=unhex(?)",id);
    }
    void migrateAndReplay() {
        flyway.migrate();flyway.validate();flyway.migrate();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='2026101101' and success=1",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns where table_schema=database() and table_name='payments' and column_name='gateway_intent_id'",String.class)).isEqualTo("NO");
    }
    @Test void missingColumnBackfillsOnlyUnverifiedIdentifierAndPreservesBusinessFields() {
        String id=payment();var before=businessFields(id);migrateAndReplay();
        assertThat(businessFields(id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select gateway_intent_id from payments where id=unhex(?)",String.class,id)).isEqualTo("LEGACY-UNVERIFIED-"+id);
        assertThatThrownBy(()->jdbc.update("update payments set gateway_intent_id=null")).isInstanceOf(Exception.class);
    }
    @Test void nullableExistingColumnPreservesKnownReferencesAndFillsNullAndBlank() {
        String simulated=payment(), legacy=payment(), blank=payment();
        jdbc.execute("alter table payments add gateway_intent_id varchar(100) null");
        jdbc.update("update payments set gateway_intent_id='SIMULATED-known-reference' where id=unhex(?)",simulated);
        jdbc.update("update payments set gateway_intent_id='   ' where id=unhex(?)",blank);
        var before=businessFields(simulated);migrateAndReplay();
        assertThat(businessFields(simulated)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select gateway_intent_id from payments where id=unhex(?)",String.class,simulated)).isEqualTo("SIMULATED-known-reference");
        for(String id:new String[]{legacy,blank})assertThat(jdbc.queryForObject("select gateway_intent_id from payments where id=unhex(?)",String.class,id)).isEqualTo("LEGACY-UNVERIFIED-"+id);
    }
    @Test void alreadyCorrectHibernateColumnAndUniqueIndexArePreserved() {
        String id=payment();jdbc.execute("alter table payments add gateway_intent_id varchar(100) null");
        jdbc.update("update payments set gateway_intent_id='EXISTING-reference'");
        jdbc.execute("alter table payments modify gateway_intent_id varchar(100) not null, add unique key existing_gateway_unique(gateway_intent_id)");
        var before=businessFields(id);migrateAndReplay();
        assertThat(businessFields(id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select gateway_intent_id from payments",String.class)).isEqualTo("EXISTING-reference");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.statistics where table_schema=database() and table_name='payments' and column_name='gateway_intent_id' and non_unique=0",Long.class)).isEqualTo(1);
    }
    @Test void existingCharacterSetAndCollationArePreserved() {
        String id=payment();jdbc.execute("alter table payments add gateway_intent_id varchar(100) character set ascii collate ascii_bin null");
        migrateAndReplay();assertThat(businessFields(id).get("status")).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select collation_name from information_schema.columns where table_schema=database() and table_name='payments' and column_name='gateway_intent_id'",String.class)).isEqualTo("ascii_bin");
    }
    @Test void duplicateExistingEvidenceStopsBeforeChangingRowsOrColumn() {
        payment();payment();jdbc.execute("alter table payments add gateway_intent_id varchar(100) null");
        jdbc.update("update payments set gateway_intent_id='EXISTING-duplicate'");
        assertThatThrownBy(()->flyway.migrate()).hasStackTraceContaining("identifiers collide");
        assertThat(jdbc.queryForObject("select count(*) from payments where gateway_intent_id='EXISTING-duplicate'",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns where table_schema=database() and table_name='payments' and column_name='gateway_intent_id'",String.class)).isEqualTo("YES");
    }
    @Test void oversizedColumnStopsWithoutTruncatingEvidence() {
        payment();jdbc.execute("alter table payments add gateway_intent_id varchar(200) null");
        jdbc.update("update payments set gateway_intent_id=?","X".repeat(150));
        assertThatThrownBy(()->flyway.migrate()).hasStackTraceContaining("without truncating evidence");
        assertThat(jdbc.queryForObject("select gateway_intent_id from payments",String.class)).hasSize(150);
    }
    @Test void conflictingIndexNameStopsBeforeBackfill() {
        payment();jdbc.execute("alter table payments add gateway_intent_id varchar(100) null, add index uk_payments_gateway_intent_id(status)");
        assertThatThrownBy(()->flyway.migrate()).hasStackTraceContaining("index name is already used");
        assertThat(jdbc.queryForObject("select count(*) from payments where gateway_intent_id is null",Long.class)).isEqualTo(1);
    }
    @Test void legacyReferenceCollisionStopsBeforeReplacingMissingReference() {
        String first=payment(),second=payment();jdbc.execute("alter table payments add gateway_intent_id varchar(100) null");
        jdbc.update("update payments set gateway_intent_id=? where id=unhex(?)","LEGACY-UNVERIFIED-"+first,second);
        assertThatThrownBy(()->flyway.migrate()).hasStackTraceContaining("identifiers collide");
        assertThat(jdbc.queryForObject("select count(*) from payments where gateway_intent_id is null",Long.class)).isEqualTo(1);
    }
}
