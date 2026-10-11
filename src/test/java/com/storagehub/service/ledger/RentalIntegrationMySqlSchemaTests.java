package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;

import com.storagehub.service.communication.CommunicationSchemaGuard;
import com.storagehub.service.communication.NotificationOutbox;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in, real MySQL verification. Never accepts a shared DB or a nonempty schema. */
@EnabledIfEnvironmentVariable(named="STORAGEHUB_SCHEMA_TEST_URL", matches=".+")
class RentalIntegrationMySqlSchemaTests {
    static HikariDataSource datasource;
    static JdbcTemplate jdbc;
    static Flyway flyway;
    static TransactionTemplate tx;

    @BeforeAll static void migrateFreshTestSchema() {
        String url=System.getenv("STORAGEHUB_SCHEMA_TEST_URL");
        assertThat(url).matches("^jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/storagehub_schema_test_[a-z0-9_]+(?:\\?.*)?$");
        datasource=new HikariDataSource();
        datasource.setJdbcUrl(url);
        datasource.setUsername(System.getenv("STORAGEHUB_SCHEMA_TEST_USERNAME"));
        datasource.setPassword(System.getenv("STORAGEHUB_SCHEMA_TEST_PASSWORD"));
        datasource.setMaximumPoolSize(2);
        jdbc=new JdbcTemplate(datasource);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database()",Long.class))
            .as("Refuse to migrate an existing or shared schema; use a fresh isolated test DB")
            .isZero();
        flyway=com.storagehub.config.DevelopMigrationHistoryLocations.configure(Flyway.configure().dataSource(datasource)
            .locations("classpath:db/migration","classpath:db/paymentmigration")
            .callbacks(new com.storagehub.config.LedgerMigrationSafetyCallback())
            .outOfOrder(false).cleanDisabled(true)).load();
        String expectedDatabase=url.substring(url.indexOf("/storagehub_schema_test_")+1).split("\\?",2)[0];
        new com.storagehub.config.RentalSupportSchemaRolloutConfiguration()
            .rentalSupportSchemaMigration(expectedDatabase).migrate(flyway);
        tx=new TransactionTemplate(new DataSourceTransactionManager(datasource));
    }

    @AfterAll static void closeConnections() { if(datasource!=null)datasource.close(); }

    @Test void migrationAndBothRuntimeGuardsPassOnMySql() {
        flyway.validate();
        new LedgerSchemaGuard(jdbc).afterPropertiesSet();
        new CommunicationSchemaGuard(jdbc).afterPropertiesSet();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name in ('rental_ledger_accounts','rental_ledger_obligations','rental_ledger_receipts','rental_ledger_allocations','rental_ledger_refunds','rental_ledger_commands','notification_outbox')",Long.class))
            .isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and (table_name like 'duong_ledger_%' or table_name='duong_notification_outbox')",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='2026101103' and success=1",Long.class)).isEqualTo(1);
    }
    @Test void paymentGatewayMigrationInstallsColumnAndUniqueIndex() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='2026101101' and success=1",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns where table_schema=database() and table_name='payments' and column_name='gateway_intent_id'",String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("select character_maximum_length from information_schema.columns where table_schema=database() and table_name='payments' and column_name='gateway_intent_id'",Long.class)).isEqualTo(100);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.statistics where table_schema=database() and table_name='payments' and column_name='gateway_intent_id' and non_unique=0",Long.class)).isEqualTo(1);
    }
    @Test void rolloutStrategyReplaysInstalledSchemaWithoutCreatingDuplicateHistory() {
        var expected=jdbc.queryForObject("select database()",String.class);
        long before=jdbc.queryForObject("select count(*) from flyway_schema_history",Long.class);
        new com.storagehub.config.RentalSupportSchemaRolloutConfiguration().rentalSupportSchemaMigration(expected).migrate(flyway);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history",Long.class)).isEqualTo(before);
    }

    @Test void textUuidBridgesUseBinaryForeignKeysWithoutChangingSharedIds() {
        assertThat(jdbc.queryForObject("select column_type from information_schema.columns where table_schema=database() and table_name='rentals' and column_name='id'",String.class)).isEqualTo("binary(16)");
        assertThat(jdbc.queryForObject("select generation_expression from information_schema.columns where table_schema=database() and table_name='rental_ledger_accounts' and column_name='rental_uuid'",String.class)).containsIgnoringCase("unhex");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.key_column_usage where constraint_schema=database() and referenced_table_name in ('rentals','users','facilities','renewals','file_assets','notifications') and table_name in ('rental_ledger_accounts','rental_ledger_obligations','rental_ledger_receipts','rental_ledger_commands','notification_outbox')",Long.class)).isEqualTo(9);
        var ledger=new LedgerStore(jdbc);
        assertThatThrownBy(()->tx.executeWithoutResult(s->ledger.open(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID())))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from rental_ledger_accounts",Long.class)).isZero();
    }

    @Test void notificationForUnknownRecipientCannotCreateAnOutboxRow() {
        var outbox=new NotificationOutbox(jdbc);
        UUID resource=UUID.randomUUID();
        assertThatThrownBy(()->tx.executeWithoutResult(s->outbox.enqueue(UUID.randomUUID(),resource,"OVERDUE",null,"test-policy","v1","Isolated schema test","schema-test",Instant.now())))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from notification_outbox where resource_id=?",Long.class,resource.toString())).isZero();
    }

    @Test void mysqlOutboxCommitsReadbackReplaysAndKeepsReceiptDistinctions() {
        UUID recipient=UUID.randomUUID(),resource=UUID.randomUUID(),notification=UUID.randomUUID();
        var outbox=new NotificationOutbox(jdbc);
        var now=Instant.now();
        tx.executeWithoutResult(s->jdbc.update("insert into users(id,created_at,updated_at,email,password_hash,full_name,status,must_change_password) values(unhex(?),now(6),now(6),?,'test-only-not-a-login','ISOLATED SCHEMA TEST','ACTIVE',false)",hex(recipient),recipient+"@isolated.invalid"));
        UUID id=tx.execute(s->outbox.enqueue(recipient,resource,"OVERDUE",null,"test-policy","v1","Isolated schema test","mysql-readback",now));
        assertThat(new NotificationOutbox(new JdbcTemplate(datasource)).read(id,false).orElseThrow().status()).isEqualTo("PENDING");
        UUID queuedReplay=tx.execute(s->outbox.enqueue(recipient,resource,"OVERDUE",null,"test-policy","v1","Isolated schema test","mysql-readback",now.plusSeconds(1)));
        assertThat(queuedReplay).isEqualTo(id);
        assertThat(jdbc.queryForObject("select count(*) from notification_outbox where resource_id=?",Long.class,resource.toString())).isEqualTo(1);
        assertThatThrownBy(()->tx.executeWithoutResult(s->outbox.inbox(id,UUID.randomUUID())))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(outbox.read(id,false).orElseThrow().status()).isEqualTo("PENDING");
        tx.executeWithoutResult(s->{
            jdbc.update("insert into notifications(id,created_at,updated_at,user_id,type,title,content,related_entity_id,is_read) values(unhex(?),now(6),now(6),unhex(?),'OVERDUE','ISOLATED SCHEMA TEST','Isolated schema test',unhex(?),false)",hex(notification),hex(recipient),hex(resource));
            outbox.inbox(id,notification);
        });
        var inbox=outbox.read(id,false).orElseThrow();
        assertThat(inbox.status()).isEqualTo("INBOX");
        assertThat(inbox.acknowledgedAt()).isNull();
        var first=tx.execute(s->outbox.acknowledge(id,recipient,notification,now));
        var replay=tx.execute(s->outbox.acknowledge(id,recipient,notification,now.plusSeconds(5)));
        assertThat(first.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(replay.acknowledgedAt()).isEqualTo(first.acknowledgedAt());
    }

    private static String hex(UUID id) { return id.toString().replace("-",""); }
}
