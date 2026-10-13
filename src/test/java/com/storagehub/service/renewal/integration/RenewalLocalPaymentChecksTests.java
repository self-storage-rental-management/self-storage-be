package com.storagehub.service.renewal.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.storagehub.domain.model.*;
import com.storagehub.service.ledger.LedgerStore;
import com.storagehub.service.overdue.OverdueSources;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class RenewalLocalPaymentChecksTests {
    private final EntityManager em=mock(EntityManager.class);
    private final LedgerStore ledger=mock(LedgerStore.class);
    private final UUID id=UUID.randomUUID(),customer=UUID.randomUUID(),facility=UUID.randomUUID(),renewal=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-10-13T01:00:00Z");
    private final Rental rental=mock(Rental.class);
    private RenewalLocalPaymentChecks checks() {
        var user=mock(User.class);var f=mock(Facility.class);var reservation=mock(Reservation.class);
        when(rental.getId()).thenReturn(id);when(rental.getCustomer()).thenReturn(user);when(user.getId()).thenReturn(customer);
        when(rental.getFacility()).thenReturn(f);when(f.getId()).thenReturn(facility);when(rental.getReservation()).thenReturn(reservation);
        TypedQuery<Long> query=mock(TypedQuery.class);
        when(em.createQuery(anyString(),eq(Long.class))).thenReturn(query);
        when(query.setParameter(anyString(),any())).thenReturn(query);when(query.getSingleResult()).thenReturn(0L);
        var beans=new StaticListableBeanFactory();beans.addBean("ledger",ledger);
        return new RenewalLocalPaymentChecks(em,beans.getBeanProvider(LedgerStore.class),beans.getBeanProvider(OverdueSources.TermSource.class));
    }
    @Test void knownUnpaidPastObligationStillBlocksPayment() {
        var c=checks();var due=new LedgerStore.Obligation(UUID.randomUUID(),null,LedgerStore.Kind.RENT,BigDecimal.TEN,now.minusSeconds(1),"old",BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.TEN);
        when(ledger.read(id)).thenReturn(Optional.of(new LedgerStore.Snapshot(id,customer,facility,0,List.of(due),List.of(),List.of())));
        assertThatThrownBy(()->c.requireKnownFinance(rental,renewal,now)).hasMessageContaining("Recorded due obligations");
    }
    @Test void currentRenewalObligationIsNotTreatedAsPriorDebt() {
        var c=checks();var due=new LedgerStore.Obligation(UUID.randomUUID(),renewal,LedgerStore.Kind.RENEWAL_REMAINDER,BigDecimal.TEN,now,"current",BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.TEN);
        when(ledger.read(id)).thenReturn(Optional.of(new LedgerStore.Snapshot(id,customer,facility,0,List.of(due),List.of(),List.of())));
        c.requireKnownFinance(rental,renewal,now);
    }
    @Test void accountBindingMismatchIsNeverAccepted() {
        var c=checks();when(ledger.read(id)).thenReturn(Optional.of(new LedgerStore.Snapshot(id,UUID.randomUUID(),facility,0,List.of(),List.of(),List.of())));
        assertThatThrownBy(()->c.requireKnownFinance(rental,renewal,now)).hasMessageContaining("binding changed");
    }
    @Test void absentPriorLedgerIsAllowedByTheExplicitNarrowPolicy() {
        var c=checks();when(ledger.read(id)).thenReturn(Optional.empty());c.requireKnownFinance(rental,renewal,now);
        verify(ledger,never()).open(any(),any(),any());
    }
}
