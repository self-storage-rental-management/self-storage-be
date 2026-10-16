package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.domain.model.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class RenewalDepositForfeituresTests {
    final com.zaxxer.hikari.HikariDataSource ds=pool();
    final JdbcTemplate jdbc=new JdbcTemplate(ds);
    final LedgerStore ledger=new LedgerStore(jdbc);
    final RenewalDepositForfeitures forfeitures=new RenewalDepositForfeitures(jdbc);
    final TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    final UUID rental=UUID.randomUUID(),renewal=UUID.randomUUID(),actor=UUID.randomUUID(),payment=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-18T01:00:00Z");
    LedgerStore.Snapshot account;
    @BeforeEach void schema()throws Exception {
        new ResourceDatabasePopulator(new ClassPathResource("rental-ledger-test-schema.sql")).execute(ds);
        var sql=new String(new ClassPathResource("db/migration-history/legacy-deposit-1501/V2026101501__record_renewal_deposit_forfeitures.sql").getInputStream().readAllBytes(),StandardCharsets.UTF_8).replace("ENGINE=InnoDB","");
        for(var statement:sql.split(";"))if(!statement.isBlank())jdbc.execute(statement);
        var beans=new StaticListableBeanFactory();beans.addBean("forfeitures",forfeitures);
        ReflectionTestUtils.setField(ledger,"depositForfeitures",beans.getBeanProvider(RenewalDepositForfeitures.class));
        tx.executeWithoutResult(s->{
            ledger.open(rental,UUID.randomUUID(),UUID.randomUUID());
            var obligation=ledger.charge(rental,renewal,LedgerStore.Kind.RENEWAL_DEPOSIT,new BigDecimal("6600000"),now,"accepted:DEPOSIT",actor,"charge",0);
            ledger.receive(rental,LedgerStore.Method.CASH,new BigDecimal("6600000"),Map.of(obligation,new BigDecimal("6600000")),"receipt",UUID.randomUUID(),actor,"receive",1,now);
        });account=ledger.read(rental).orElseThrow();
    }
    @AfterEach void close(){ds.close();}
    private static com.zaxxer.hikari.HikariDataSource pool(){var ds=new com.zaxxer.hikari.HikariDataSource();ds.setJdbcUrl("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL");ds.setMaximumPoolSize(4);ds.setMinimumIdle(1);return ds;}
    @Test void forfeitureIsImmutableDoesNotChargeAgainAndBlocksRefunds() {
        tx.executeWithoutResult(s->record(account));
        var after=ledger.read(rental).orElseThrow();assertThat(after.knownOutstanding()).isZero();assertThat(after.obligations()).hasSize(1);assertThat(after.receipts()).hasSize(1);
        tx.executeWithoutResult(s->record(after));
        assertThat(jdbc.queryForObject("select count(*) from renewal_deposit_forfeitures",Long.class)).isEqualTo(1);
        assertThat(ledger.read(rental).orElseThrow().revision()).isEqualTo(after.revision());
        assertThatThrownBy(()->tx.execute(s->ledger.reserveRefund(rental,after.receipts().getFirst().id(),after.obligations().getFirst().id(),new BigDecimal("6600000"),"refund",actor,"refund",after.revision())))
            .hasMessageContaining("not refundable");
    }
    @Test void reservedRefundPreventsForfeitureAndRollbackLeavesNoDisposition() {
        tx.execute(s->ledger.reserveRefund(rental,account.receipts().getFirst().id(),account.obligations().getFirst().id(),new BigDecimal("6600000"),"approved",actor,"refund",account.revision()));
        account=ledger.read(rental).orElseThrow();
        assertThatThrownBy(()->tx.executeWithoutResult(s->record(account))).hasMessageContaining("reserved or executed refund");
        assertThat(jdbc.queryForObject("select count(*) from renewal_deposit_forfeitures",Long.class)).isZero();
    }
    @Test void originalRentalSecurityDepositCannotBeForfeitedByRenewalRule() {
        var old=account.obligations().getFirst();var security=new LedgerStore.Obligation(old.id(),null,LedgerStore.Kind.SECURITY_DEPOSIT,old.amount(),old.dueAt(),old.sourceRef(),old.allocated(),old.refunded(),old.outstanding());
        var wrong=new LedgerStore.Snapshot(rental,account.customerId(),account.facilityId(),account.revision(),List.of(security),account.receipts(),account.refunds());
        assertThatThrownBy(()->tx.executeWithoutResult(s->forfeitures.record(renewal,payment,wrong,security,account.receipts().getFirst(),"PRODUCTION",now)))
            .hasMessageContaining("original allocated Renewal deposit");
        assertThat(jdbc.queryForObject("select count(*) from renewal_deposit_forfeitures",Long.class)).isZero();
    }
    private void record(LedgerStore.Snapshot s){forfeitures.record(renewal,payment,s,s.obligations().getFirst(),s.receipts().getFirst(),"PRODUCTION",now);}
}
