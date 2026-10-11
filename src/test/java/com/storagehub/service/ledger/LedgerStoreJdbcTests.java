package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.common.api.ApiException;
import com.storagehub.domain.model.Rental;
import com.storagehub.service.RentalReadSources;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual H2 commits/readbacks/rollback/concurrent connections. Never loads a shared datasource. */
class LedgerStoreJdbcTests {
    static final Instant NOW=Instant.parse("2026-10-09T10:00:00Z");
    com.zaxxer.hikari.HikariDataSource datasource;JdbcTemplate jdbc;TransactionTemplate tx;LedgerStore store;
    UUID rental,customer,facility,actor;
    @BeforeEach void setup(){
        datasource=new com.zaxxer.hikari.HikariDataSource();datasource.setJdbcUrl("jdbc:h2:mem:rental-ledger-"+UUID.randomUUID()+";MODE=MySQL;LOCK_TIMEOUT=5000");datasource.setUsername("sa");datasource.setPassword("");datasource.setMaximumPoolSize(4);datasource.setMinimumIdle(1);
        new ResourceDatabasePopulator(new ClassPathResource("rental-ledger-test-schema.sql")).execute(datasource);
        jdbc=new JdbcTemplate(datasource);tx=new TransactionTemplate(new DataSourceTransactionManager(datasource));store=new LedgerStore(jdbc);
        rental=UUID.randomUUID();customer=UUID.randomUUID();facility=UUID.randomUUID();actor=UUID.randomUUID();
        tx.executeWithoutResult(s->store.open(rental,customer,facility));
    }
    @AfterEach void cleanup(){datasource.close();}
    UUID charge(String amount){return tx.execute(s->store.charge(rental,null,LedgerStore.Kind.RENT,new BigDecimal(amount),NOW.minusSeconds(60),"invoice:"+UUID.randomUUID(),actor,UUID.randomUUID().toString(),revision()));}
    long revision(){return store.read(rental).orElseThrow().revision();}
    UUID receive(UUID charge,String amount,LedgerStore.Method method,String reference,String key){return tx.execute(s->store.receive(rental,method,new BigDecimal(amount),Map.of(charge,new BigDecimal(amount)),reference,UUID.nameUUIDFromBytes(reference.getBytes(java.nio.charset.StandardCharsets.UTF_8)),actor,key,revision(),NOW));}
    @Test void commitReadbackUsesActualRowsNotInMemoryBalances(){
        UUID id=charge("100");receive(id,"60",LedgerStore.Method.CASH,"cash-1","receipt-1");
        var fresh=new LedgerStore(new JdbcTemplate(datasource)).read(rental).orElseThrow();
        assertThat(fresh.knownOutstanding()).isEqualByComparingTo("40");assertThat(fresh.revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from rental_ledger_allocations",Long.class)).isEqualTo(1);
    }
    @Test void missingAccountIsUnknownNotVerifiedZero(){assertThat(store.read(UUID.randomUUID())).isEmpty();}
    @Test void openingAnEmptyAccountDoesNotCertifyDebt(){assertThat(store.read(rental).orElseThrow().obligations()).isEmpty();assertThat(adapters(null).read(fakeRental())).isEmpty();}
    @Test void writeWithoutTransactionIsRejected(){assertThatThrownBy(()->store.charge(rental,null,LedgerStore.Kind.RENT,BigDecimal.TEN,NOW,"invoice",actor,"key",0)).isInstanceOf(IllegalStateException.class);}
    @Test void readOnlyTransactionCannotWrite(){var readOnly=new TransactionTemplate(tx.getTransactionManager());readOnly.setReadOnly(true);assertThatThrownBy(()->readOnly.executeWithoutResult(s->store.open(rental,customer,facility))).isInstanceOf(IllegalStateException.class);}
    @Test void accountCannotBeRebound(){assertThatThrownBy(()->tx.execute(s->{store.open(rental,UUID.randomUUID(),facility);return null;})).isInstanceOf(ApiException.class);}
    @Test void sourceChargeIsPostedOnlyOnce(){
        UUID first=tx.execute(s->store.charge(rental,null,LedgerStore.Kind.RENT,BigDecimal.TEN,NOW,"invoice",actor,"charge-a",0));
        UUID retry=tx.execute(s->store.charge(rental,null,LedgerStore.Kind.RENT,BigDecimal.TEN,NOW,"invoice",actor,"charge-a",0));
        UUID secondKey=tx.execute(s->store.charge(rental,null,LedgerStore.Kind.RENT,BigDecimal.TEN,NOW,"invoice",actor,"charge-b",1));
        assertThat(first).isEqualTo(retry).isEqualTo(secondKey);assertThat(revision()).isEqualTo(1);
    }
    @Test void sourceChargeCannotChangeTerms(){charge("10");tx.executeWithoutResult(s->store.charge(rental,null,LedgerStore.Kind.RENT,BigDecimal.TEN,NOW,"fixed",actor,"a",revision()));assertThatThrownBy(()->tx.execute(s->store.charge(rental,null,LedgerStore.Kind.RENT,new BigDecimal("20"),NOW,"fixed",actor,"b",revision()))).isInstanceOf(ApiException.class);}
    @Test void staleRevisionCannotPost(){charge("10");assertThatThrownBy(()->tx.execute(s->store.charge(rental,null,LedgerStore.Kind.RENT,BigDecimal.TEN,NOW,"invoice",actor,"stale",0))).isInstanceOf(ApiException.class);}
    @Test void invalidVndAmountsAreRejected(){for(String amount:List.of("-1","0.5","1000000000000000000"))assertThatThrownBy(()->charge(amount)).isInstanceOf(ApiException.class);}
    @Test void receiptReplayIgnoresLaterServerClockButNotChangedPayload(){
        UUID o=charge("100");UUID file=UUID.randomUUID();
        UUID first=tx.execute(s->store.receive(rental,LedgerStore.Method.CASH,new BigDecimal("50"),Map.of(o,new BigDecimal("50")),"cash-proof",file,actor,"same",revision(),NOW));
        UUID repeat=tx.execute(s->store.receive(rental,LedgerStore.Method.CASH,new BigDecimal("50"),Map.of(o,new BigDecimal("50")),"cash-proof",file,actor,"same",0,NOW.plusSeconds(60)));
        assertThat(repeat).isEqualTo(first);
        assertThatThrownBy(()->tx.execute(s->store.receive(rental,LedgerStore.Method.CASH,new BigDecimal("40"),Map.of(o,new BigDecimal("40")),"cash-proof",file,actor,"same",revision(),NOW))).isInstanceOf(ApiException.class);
    }
    @Test void sameReceiptEvidenceCannotBeUsedTwice(){UUID o=charge("100");receive(o,"30",LedgerStore.Method.CASH,"unique-proof","a");assertThatThrownBy(()->receive(o,"30",LedgerStore.Method.CASH,"unique-proof","b")).isInstanceOf(ApiException.class);assertThat(store.read(rental).orElseThrow().knownOutstanding()).isEqualByComparingTo("70");}
    @Test void allocationMustExactlyMatchReceiptAndResource(){UUID o=charge("100");for(var allocation:List.of(Map.of(o,BigDecimal.TEN),Map.of(UUID.randomUUID(),new BigDecimal("20"))))assertThatThrownBy(()->tx.execute(s->store.receive(rental,LedgerStore.Method.CASH,new BigDecimal("20"),allocation,"proof",UUID.randomUUID(),actor,"a",revision(),NOW))).isInstanceOf(ApiException.class);assertThat(store.read(rental).orElseThrow().receipts()).isEmpty();}
    @Test void overpaymentIsNotSilentlyAllocated(){UUID o=charge("100");assertThatThrownBy(()->receive(o,"101",LedgerStore.Method.CASH,"proof","a")).isInstanceOf(ApiException.class);}
    @Test void simulationNeverReducesDebtOrHeldDeposit(){UUID o=charge("100");receive(o,"100",LedgerStore.Method.SIMULATED,"simulation","sim");assertThat(store.read(rental).orElseThrow().knownOutstanding()).isEqualByComparingTo("100");receive(o,"100",LedgerStore.Method.CASH,"actual","real");assertThat(store.read(rental).orElseThrow().knownOutstanding()).isZero();}
    @Test void simulationCannotBeRefunded(){UUID o=charge("100");UUID p=receive(o,"100",LedgerStore.Method.SIMULATED,"sim","sim");assertThatThrownBy(()->tx.execute(s->store.reserveRefund(rental,p,o,BigDecimal.TEN,"decision",actor,"r",revision()))).isInstanceOf(ApiException.class);}
    @Test void reservationIsNotPayoutAndCannotOverReserve(){
        UUID o=charge("100"),p=receive(o,"100",LedgerStore.Method.CASH,"paid","p");
        tx.executeWithoutResult(s->store.reserveRefund(rental,p,o,new BigDecimal("60"),"approved-1",actor,"r1",revision()));
        assertThat(store.read(rental).orElseThrow().refunds().getFirst().status()).isEqualTo("RESERVED");assertThat(store.read(rental).orElseThrow().knownOutstanding()).isZero();
        assertThatThrownBy(()->tx.execute(s->store.reserveRefund(rental,p,o,new BigDecimal("41"),"approved-2",actor,"r2",revision()))).isInstanceOf(ApiException.class);
    }
    @Test void payoutIsOnceOnlyAndReopensTheRefundedObligation(){
        UUID o=charge("100"),p=receive(o,"100",LedgerStore.Method.CASH,"paid","p");UUID refund=tx.execute(s->store.reserveRefund(rental,p,o,new BigDecimal("60"),"approved",actor,"r",revision()));
        long revision=revision();tx.executeWithoutResult(s->store.executeRefund(rental,refund,"bank-payout",actor,"exec",revision));tx.executeWithoutResult(s->store.executeRefund(rental,refund,"bank-payout",actor,"exec",revision));
        assertThat(store.read(rental).orElseThrow().knownOutstanding()).isEqualByComparingTo("60");
        assertThatThrownBy(()->tx.execute(s->store.executeRefund(rental,refund,"another-payout",actor,"exec2",revision()))).isInstanceOf(ApiException.class);
    }
    @Test void failureAfterReceiptRollsBackMoneyAllocationsAndKey(){
        UUID o=charge("100");assertThatThrownBy(()->tx.execute(s->{store.receive(rental,LedgerStore.Method.CASH,new BigDecimal("100"),Map.of(o,new BigDecimal("100")),"rollback",UUID.randomUUID(),actor,"rollback",revision(),NOW);throw new IllegalStateException("Downstream command failed");})).isInstanceOf(IllegalStateException.class);
        assertThat(store.read(rental).orElseThrow().receipts()).isEmpty();assertThat(revision()).isEqualTo(1);
        receive(o,"100",LedgerStore.Method.CASH,"rollback","rollback");assertThat(store.read(rental).orElseThrow().knownOutstanding()).isZero();
    }
    @Test void sameKeyCannotBeReusedForAnotherRental(){
        UUID o=charge("100"),file=UUID.randomUUID();tx.executeWithoutResult(s->store.receive(rental,LedgerStore.Method.CASH,new BigDecimal("10"),Map.of(o,BigDecimal.TEN),"paid",file,actor,"global-key",revision(),NOW));
        UUID other=UUID.randomUUID();tx.executeWithoutResult(s->store.open(other,customer,facility));
        assertThatThrownBy(()->tx.execute(s->store.receive(other,LedgerStore.Method.CASH,BigDecimal.TEN,Map.of(o,BigDecimal.TEN),"paid",file,actor,"global-key",0,NOW))).isInstanceOf(ApiException.class);
    }
    @Test void parallelCollectionsCannotPayTheSameBalanceTwice()throws Exception{
        UUID o=charge("100");long revision=revision();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {List<Future<Boolean>> results=new ArrayList<>();for(int i=0;i<2;i++){String key="concurrent-"+i;results.add(pool.submit(()->{start.await();try{tx.executeWithoutResult(s->store.receive(rental,LedgerStore.Method.CASH,new BigDecimal("100"),Map.of(o,new BigDecimal("100")),key,UUID.randomUUID(),actor,key,revision,NOW));return true;}catch(ApiException e){return false;}}));}start.countDown();int successes=0;for(var r:results)if(r.get(10,TimeUnit.SECONDS))successes++;assertThat(successes).isEqualTo(1);assertThat(store.read(rental).orElseThrow().receipts()).hasSize(1);}
        finally{pool.shutdownNow();}
    }
    @Test void absentCoverageNeverMakesThreeFinancialPortsComplete(){charge("100");var a=adapters(null);assertThat(a.read(fakeRental())).isEmpty();assertThat(a.approval(fakeRental())).isEmpty();assertThat(a.read(fakeRental(),NOW)).isEmpty();assertThat(a.consistentThroughApproval()).isFalse();}
    @Test void validCoverageMapsDebtAndPreservesUnknownSecurityDeposit(){
        charge("100");var a=adapters((r,s,n)->Optional.of(new LedgerCoverageSource.Coverage(r.getId(),s.revision(),"actual-writer-proof",n,false,true,false,RentalReadSources.BillingMode.OTHER)));
        assertThat(a.read(fakeRental()).orElseThrow().outstandingAmount()).isEqualByComparingTo("100");assertThat(a.read(fakeRental()).orElseThrow().securityDepositAmount()).isNull();assertThat(a.approval(fakeRental()).orElseThrow().dueObligations()).hasSize(1);assertThat(a.read(fakeRental(),NOW).orElseThrow().obligations()).hasSize(1);assertThat(a.consistentThroughApproval()).isFalse();
    }
    @Test void staleOrWrongResourceCoverageIsRejected(){for(boolean wrong:List.of(false,true)){var a=adapters((r,s,n)->Optional.of(new LedgerCoverageSource.Coverage(wrong?UUID.randomUUID():r.getId(),wrong?s.revision():s.revision()+1,"proof",n,true,true,false,RentalReadSources.BillingMode.OTHER)));assertThat(a.read(fakeRental())).isEmpty();}}
    @Test void enabledWithoutSchemaFailsWithoutCreatingTables(){var empty=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:empty-"+UUID.randomUUID(),"sa",""));assertThatThrownBy(()->new LedgerSchemaGuard(empty).afterPropertiesSet()).isInstanceOf(org.springframework.dao.DataAccessException.class);new LedgerSchemaGuard(jdbc).afterPropertiesSet();}
    @Test void disabledFeatureDoesNotRegisterLedgerOrRequireNewSchema(){new ApplicationContextRunner().withUserConfiguration(LedgerStore.class,LedgerSchemaGuard.class,LedgerFinancialAdapters.class,LedgerApprovalFinancialAdapter.class,LedgerQueryService.class,com.storagehub.api.rental.RentalLedgerController.class).run(c->{assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(LedgerStore.class);assertThat(c).doesNotHaveBean(LedgerFinancialAdapters.class);});}
    @Test void duplicateChargeComparesThePersistedMillisecondPrecision(){Instant due=NOW.plusNanos(123456789);UUID first=tx.execute(s->store.charge(rental,null,LedgerStore.Kind.RENT,BigDecimal.TEN,due,"same-source",actor,"a",revision()));UUID second=tx.execute(s->store.charge(rental,null,LedgerStore.Kind.RENT,BigDecimal.TEN,due,"same-source",actor,"b",revision()));assertThat(second).isEqualTo(first);assertThat(store.read(rental).orElseThrow().obligations()).hasSize(1);}
    LedgerFinancialAdapters adapters(LedgerCoverageSource source){var beans=new DefaultListableBeanFactory();if(source!=null)beans.registerSingleton("coverage",source);beans.registerSingleton("clock",Clock.fixed(NOW,ZoneOffset.UTC));return new LedgerFinancialAdapters(store,beans.getBeanProvider(LedgerCoverageSource.class),beans.getBeanProvider(Clock.class));}
    Rental fakeRental(){var r=new Rental();var c=new com.storagehub.domain.model.User();var f=new com.storagehub.domain.model.Facility();org.springframework.test.util.ReflectionTestUtils.setField(r,"id",rental);org.springframework.test.util.ReflectionTestUtils.setField(c,"id",customer);org.springframework.test.util.ReflectionTestUtils.setField(f,"id",facility);r.setCustomer(c);r.setFacility(f);return r;}
}
