package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.PaymentRepository;
import com.storagehub.service.*;
import com.storagehub.service.overdue.OverdueSources;
import com.storagehub.service.renewal.operations.*;
import com.storagehub.service.renewal.persistence.RenewalPersistence;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

class RenewalDepositDeadlineTests {
    final Instant dayEight=Instant.parse("2026-10-18T01:00:00Z");
    final UUID id=UUID.randomUUID();
    final EntityManager em=mock(EntityManager.class);
    final LedgerStore ledger=mock(LedgerStore.class);
    final RenewalPersistence store=mock(RenewalPersistence.class);
    final OverdueSources.TermSource terms=mock(OverdueSources.TermSource.class);
    final Renewal n=new Renewal();
    RenewalDepositSettlementService service(Instant now) {
        ReflectionTestUtils.setField(n,"id",id);n.setStatus(RenewalStatus.deposit_paid);
        var rental=new Rental();ReflectionTestUtils.setField(rental,"id",UUID.randomUUID());n.setRental(rental);
        when(em.find(Renewal.class,id)).thenReturn(n);when(store.lockRental(rental.getId())).thenReturn(rental);
        var s=new RenewalOperationState(n);s.deposit(UUID.randomUUID(),dayEight.minus(Duration.ofDays(6)),dayEight.minus(Duration.ofDays(3)),dayEight.minusSeconds(15*3600),"policy","1");
        when(em.find(RenewalOperationState.class,id,LockModeType.PESSIMISTIC_WRITE)).thenReturn(s);
        when(terms.term(rental,now)).thenReturn(Optional.of(new OverdueSources.Term("policy","1",LocalDate.of(2026,10,10),3,6,7,8,dayEight.minusSeconds(15*3600),dayEight)));
        var beans=new StaticListableBeanFactory();beans.addBean("clock",Clock.fixed(now,ZoneOffset.UTC));beans.addBean("terms",terms);
        var service=new RenewalDepositSettlementService(em,new ObjectMapper(),ledger,mock(PaymentRepository.class),mock(PaymentProvenance.class),store,mock(AuditLogService.class),mock(NotificationService.class),
            beans.getBeanProvider(Clock.class),beans.getBeanProvider(RenewalOperationSources.PolicySource.class),beans.getBeanProvider(RenewalOperationSources.CalendarSource.class),
            beans.getBeanProvider(RenewalOperationSources.SafetySource.class),beans.getBeanProvider(RenewalOperationService.class));
        ReflectionTestUtils.setField(service,"overdueTerms",beans.getBeanProvider(OverdueSources.TermSource.class));
        ReflectionTestUtils.setField(service,"faultGuard",mock(RenewalDepositFaultGuard.class));return service;
    }
    @Test void expiredSigningDeadlineDoesNotForfeitBeforeDayEight(){assertThat(service(dayEight.minusSeconds(1)).reconcile(id)).isFalse();verifyNoInteractions(ledger);}
    @Test void exactDayEightRequiresVerifiedMoneyRatherThanInventingForfeiture(){var service=service(dayEight);assertThatThrownBy(()->service.reconcile(id)).hasMessageContaining("Verified deposit and accepted revision required");verifyNoInteractions(ledger);}
    @Test void completedRenewalIsNeverForfeited(){var service=service(dayEight);n.setStatus(RenewalStatus.completed);assertThat(service.reconcile(id)).isFalse();verifyNoInteractions(terms,ledger);}
}
