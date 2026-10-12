package com.storagehub.service.ledger;

import com.storagehub.domain.model.*;
import com.storagehub.service.RentalReadSources;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class LedgerFinancialAdaptersTests {
    @Test void partialOverdueProjectionRequiresMatchedUnadjustedDebtAndCorrectAccountBinding() {
        var rental=new Rental();var customer=new User();var facility=new Facility();
        for(var entity:List.of(rental,customer,facility))ReflectionTestUtils.setField(entity,"id",UUID.randomUUID());
        rental.setCustomer(customer);rental.setFacility(facility);
        var store=mock(LedgerStore.class);var inventory=mock(LedgerCoverageInventory.class);
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();beans.addBean("inventory",inventory);
        var adapters=new LedgerFinancialAdapters(store,beans.getBeanProvider(LedgerCoverageSource.class),beans.getBeanProvider(Clock.class));
        ReflectionTestUtils.setField(adapters,"inventories",beans.getBeanProvider(LedgerCoverageInventory.class));
        var now=Instant.parse("2026-10-12T00:00:00Z");var id=UUID.randomUUID();var renewal=UUID.randomUUID();
        var zero=java.math.BigDecimal.ZERO;var amount=java.math.BigDecimal.TEN;
        var debt=new LedgerStore.Obligation(id,renewal,LedgerStore.Kind.RENEWAL_DEPOSIT,amount,now.minusSeconds(1),"source",zero,zero,amount);
        var snapshot=new LedgerStore.Snapshot(rental.getId(),customer.getId(),facility.getId(),1,List.of(debt),List.of(),List.of());
        when(store.read(rental.getId())).thenReturn(Optional.of(snapshot));
        var match=new LedgerCoverageInventory.RenewalDepositEvidence(renewal,UUID.randomUUID(),"MATCHED",id);
        when(inventory.inspect(rental,Optional.of(snapshot))).thenReturn(new LedgerCoverageInventory.Inventory(rental.getId(),1L,"PARTIAL",List.of(),List.of(),false,List.of(match)));
        assertThat(adapters.read(rental,now).orElseThrow().completeness()).isEqualTo("PARTIAL");
        assertThat(adapters.read(rental,now).orElseThrow().obligations()).hasSize(1);
        assertThat(adapters.approval(rental)).isEmpty();assertThat(adapters.read(rental)).isEmpty();
        when(inventory.inspect(rental,Optional.of(snapshot))).thenReturn(new LedgerCoverageInventory.Inventory(rental.getId(),1L,"PARTIAL",List.of("RENEWAL_TERMINAL_ACCOUNTING_UNRECONCILED"),List.of(),false,List.of(match)));
        assertThat(adapters.read(rental,now)).isEmpty();
        when(inventory.inspect(rental,Optional.of(snapshot))).thenReturn(new LedgerCoverageInventory.Inventory(rental.getId(),1L,"PARTIAL",List.of(),List.of(),false,List.of()));
        assertThat(adapters.read(rental,now).orElseThrow().obligations()).isEmpty();
        for(var unsafe:List.of(
            new LedgerStore.Snapshot(rental.getId(),UUID.randomUUID(),facility.getId(),1,List.of(debt),List.of(),List.of()),
            new LedgerStore.Snapshot(rental.getId(),customer.getId(),UUID.randomUUID(),1,List.of(debt),List.of(),List.of()),
            new LedgerStore.Snapshot(rental.getId(),customer.getId(),facility.getId(),1,List.of(debt),List.of(new LedgerStore.Receipt(UUID.randomUUID(),LedgerStore.Method.VNPAY_SANDBOX,amount,"test",null,now)),List.of()),
            new LedgerStore.Snapshot(rental.getId(),customer.getId(),facility.getId(),1,List.of(debt),List.of(),List.of(new LedgerStore.Refund(UUID.randomUUID(),UUID.randomUUID(),id,amount,"PENDING","test",null))))) {
            when(store.read(rental.getId())).thenReturn(Optional.of(unsafe));assertThat(adapters.read(rental,now)).isEmpty();
        }
    }
    @Test void bindingRevisionAndCertificationTimeMustMatchBeforeFinancialProjection() {
        var rental=new Rental();var customer=new User();var facility=new Facility();
        for(var entity:List.of(rental,customer,facility))ReflectionTestUtils.setField(entity,"id",UUID.randomUUID());
        rental.setCustomer(customer);rental.setFacility(facility);
        var store=mock(LedgerStore.class);var source=mock(LedgerCoverageSource.class);
        ObjectProvider<LedgerCoverageSource> providers=mock(ObjectProvider.class);when(providers.getIfAvailable()).thenReturn(source);
        ObjectProvider<Clock> clocks=mock(ObjectProvider.class);var now=Instant.parse("2026-10-11T00:00:00Z");
        when(clocks.getIfAvailable()).thenReturn(Clock.fixed(now,ZoneOffset.UTC));
        var adapters=new LedgerFinancialAdapters(store,providers,clocks);
        var account=new LedgerStore.Snapshot(rental.getId(),customer.getId(),facility.getId(),3,List.of(),List.of(),List.of());
        when(store.read(rental.getId())).thenReturn(Optional.of(account));
        when(source.verify(rental,account,now)).thenReturn(Optional.empty());
        assertThat(adapters.read(rental)).isEmpty();assertThat(adapters.approval(rental)).isEmpty();
        assertThat(adapters.consistentThroughApproval()).isFalse();
        for(var proof:List.of(
            new LedgerCoverageSource.Coverage(UUID.randomUUID(),3,"source",now,true,true,false,RentalReadSources.BillingMode.PREPAID_FULL_PERIOD),
            new LedgerCoverageSource.Coverage(rental.getId(),2,"source",now,true,true,false,RentalReadSources.BillingMode.PREPAID_FULL_PERIOD),
            new LedgerCoverageSource.Coverage(rental.getId(),3,"source",now.plusSeconds(1),true,true,false,RentalReadSources.BillingMode.PREPAID_FULL_PERIOD))) {
            when(source.verify(rental,account,now)).thenReturn(Optional.of(proof));
            assertThat(adapters.read(rental)).isEmpty();assertThat(adapters.read(rental,now)).isEmpty();
        }
        when(store.read(rental.getId())).thenReturn(Optional.of(new LedgerStore.Snapshot(UUID.randomUUID(),customer.getId(),facility.getId(),3,List.of(),List.of(),List.of())));
        assertThat(adapters.read(rental)).isEmpty();
    }
}
