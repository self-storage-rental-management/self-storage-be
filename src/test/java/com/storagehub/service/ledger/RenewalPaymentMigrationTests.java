package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Executes the actual additive Flyway script on an isolated H2 schema. MySQL rollout is separate. */
class RenewalPaymentMigrationTests {
    @TempDir Path directory;
    @Test void migrateReplayPreserveHistoryAndRejectCrossRenewalRevisionBinding() throws Exception {
        var datasource=new DriverManagerDataSource("jdbc:h2:mem:renewal-migration-"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(datasource);
        jdbc.execute("create table activity_logs(id binary(16) primary key)");
        jdbc.execute("create table renewal_accepted_revisions(id binary(16) primary key,renewal_id binary(16) not null)");
        jdbc.execute("create table payments(id binary(16) primary key,renewal_id binary(16),renewal_revision_id binary(16),status varchar(32),amount decimal(14,2),currency varchar(3))");
        byte[] id=new byte[16],renewal=new byte[16],revision=new byte[16];renewal[0]=1;revision[0]=2;
        jdbc.update("insert into renewal_accepted_revisions values(?,?)",revision,renewal);
        jdbc.update("insert into payments values(?,?,?,'PAID',100,'VND')",id,renewal,revision);
        var before=jdbc.queryForMap("select status,amount,currency from payments");
        String script="V2026101204__renewal_payment_reconciliation.sql";
        try(var stream=new ClassPathResource("db/migration/"+script).getInputStream()){Files.copy(stream,directory.resolve(script));}
        var flyway=Flyway.configure().dataSource(datasource).locations("filesystem:"+directory.toAbsolutePath())
            .baselineOnMigrate(true).baselineVersion("2026101203").cleanDisabled(true).load();
        flyway.migrate();flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForMap("select status,amount,currency from payments")).isEqualTo(before);
        assertThat(jdbc.queryForObject("select renewal_reconciliation_attempts from payments",Integer.class)).isZero();
        byte[] otherRenewal=new byte[16];otherRenewal[0]=3;
        assertThatThrownBy(()->jdbc.update("update payments set renewal_id=?",otherRenewal)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(java.util.Arrays.stream(flyway.info().applied()).filter(info->info.getVersion()!=null
            &&"2026101204".equals(info.getVersion().toString())).count()).isEqualTo(1);
    }
}
