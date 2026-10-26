package com.storagehub.service.overdue;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.storagehub.domain.model.*;
import com.storagehub.service.*;
import com.storagehub.service.rental.period.*;
import com.storagehub.service.renewal.operations.*;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

class LocalRenewalAutoRecoveryServiceTests {
    private final EntityManager em=mock(EntityManager.class);
    private final RentalPeriodResolver periods=mock(RentalPeriodResolver.class);
    private final OverdueSources.TermSource terms=mock(OverdueSources.TermSource.class);
    private final RecoveryTaskSource receiver=mock(RecoveryTaskSource.class);
    private final RenewalOperationSources.SafetySource safety=mock(RenewalOperationSources.SafetySource.class);
    private final NotificationService notifications=mock(NotificationService.class);
    private final AuditLogService audit=mock(AuditLogService.class);
    private final UUID id=UUID.randomUUID(),facility=UUID.randomUUID();
    private final Instant start=Instant.parse("2026-10-17T17:00:00Z"); // 00:00 day 8 at the facility.
    private final Rental rental=new Rental();
    private final Renewal renewal=new Renewal();
    private TypedQuery<UUID> handoffQuery;

    @SuppressWarnings("unchecked")
    private LocalRenewalAutoRecoveryService service(Instant now,long uncertainPayments) {
        ReflectionTestUtils.setField(rental,"id",id);var f=new Facility();ReflectionTestUtils.setField(f,"id",facility);rental.setFacility(f);
        var customer=new User();ReflectionTestUtils.setField(customer,"id",UUID.randomUUID());rental.setCustomer(customer);
        var unit=new StorageUnit();unit.setStatus(StorageUnitStatus.occupied);rental.setStorageUnit(unit);
        rental.setStatus(RentalStatus.active);rental.setContractEndDate(LocalDate.of(2026,10,10));
        ReflectionTestUtils.setField(renewal,"id",UUID.randomUUID());renewal.setRental(rental);renewal.setStatus(RenewalStatus.accepted);
        when(em.find(Rental.class,id,LockModeType.PESSIMISTIC_WRITE)).thenReturn(rental);
        when(periods.read(rental)).thenReturn(RentalPeriod.verified(id,LocalDate.of(2026,7,10),rental.getContractEndDate(),RentalPeriod.Convention.INCLUSIVE,"period"));
        when(terms.term(rental,now)).thenReturn(Optional.of(new OverdueSources.Term("policy","1",rental.getContractEndDate(),3,6,7,8,start.minusSeconds(7*3600),start)));
        handoffQuery=mock(TypedQuery.class,RETURNS_SELF);when(handoffQuery.getResultList()).thenReturn(List.of());
        when(em.createQuery(anyString(),eq(UUID.class))).thenReturn(handoffQuery);
        TypedQuery<Renewal> renewalQuery=mock(TypedQuery.class,RETURNS_SELF);
        when(renewalQuery.getResultList()).thenReturn(List.of(renewal));
        when(em.createQuery(anyString(),eq(Renewal.class))).thenReturn(renewalQuery);
        TypedQuery<Long> uncertain=mock(TypedQuery.class,RETURNS_SELF),paid=mock(TypedQuery.class,RETURNS_SELF);
        when(uncertain.getSingleResult()).thenReturn(uncertainPayments);when(paid.getSingleResult()).thenReturn(0L);
        when(em.createQuery(contains("p.status in"),eq(Long.class))).thenReturn(uncertain);
        when(em.createQuery(contains("p.status=:paid"),eq(Long.class))).thenReturn(paid);
        TypedQuery<Long> preparation=mock(TypedQuery.class,RETURNS_SELF);
        when(preparation.getSingleResult()).thenReturn(0L);
        when(em.createQuery(contains("p.failureCode=:code"),eq(Long.class))).thenReturn(preparation);
        when(receiver.atomic()).thenReturn(true);when(safety.atomic()).thenReturn(true);
        when(receiver.receive(eq(rental),anyString(),anyString(),anyString(),eq(now)))
            .thenReturn(new OverdueSources.Handoff(UUID.randomUUID(),"RECEIVED"));
        var beans=new StaticListableBeanFactory();beans.addBean("terms",terms);beans.addBean("receiver",receiver);beans.addBean("safety",safety);
        beans.addBean("clock",Clock.fixed(now,ZoneOffset.UTC));
        return new LocalRenewalAutoRecoveryService(em,periods,beans.getBeanProvider(OverdueSources.TermSource.class),
            beans.getBeanProvider(OverdueSources.RecoverySource.class),beans.getBeanProvider(RenewalOperationSources.SafetySource.class),
            beans.getBeanProvider(RenewalOperationSources.CalendarSource.class),beans.getBeanProvider(Clock.class),notifications,audit);
    }
    @Test void daySevenCannotCreateRecoveryOrCancelRenewal() {
        assertThat(service(start.minusSeconds(1),0).handoff(id)).isFalse();
        verify(receiver,never()).receive(any(),anyString(),anyString(),anyString(),any());
        verify(em,never()).persist(any());assertThat(renewal.getStatus()).isEqualTo(RenewalStatus.accepted);
    }
    @Test void pendingPaymentRequiresReconciliationBeforeHandoff() {
        var service=service(start,1);
        assertThatThrownBy(()->service.handoff(id)).hasMessageContaining("Payment requires reconciliation");
        verify(receiver,never()).receive(any(),anyString(),anyString(),anyString(),any());
        verify(em,never()).persist(any());verifyNoInteractions(notifications);
    }
    @Test void dayEightCancelsUnfinishedRenewalOnceAndKeepsTheWarehouseOccupied() {
        var service=service(start,0);
        assertThat(service.handoff(id)).isTrue();
        assertThat(renewal.getStatus()).isEqualTo(RenewalStatus.cancelled);
        assertThat(rental.getStorageUnit().getStatus()).isEqualTo(StorageUnitStatus.occupied);
        assertThat(rental.getContractEndDate()).isEqualTo(LocalDate.of(2026,10,10));
        verify(em).persist(isA(OverdueFollowUp.class));verify(em).persist(isA(RenewalOperationEvent.class));
        verify(notifications).createNotification(eq(rental.getCustomer().getId()),eq(NotificationType.OVERDUE),anyString(),anyString(),eq(id));
        when(handoffQuery.getResultList()).thenReturn(List.of(UUID.randomUUID()));
        assertThat(service.handoff(id)).isFalse();
        verify(receiver,times(1)).receive(any(),anyString(),anyString(),anyString(),any());
    }
}
