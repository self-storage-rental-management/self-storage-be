package com.storagehub.service.renewal.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.storagehub.domain.model.*;
import com.storagehub.service.overdue.OverdueSources;
import com.storagehub.service.overdue.RecoveryTaskSource;
import com.storagehub.service.rental.period.*;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class LocalDatabaseRenewalWriterProtocolTests {
    EntityManager em=mock(EntityManager.class);
    RentalPeriodResolver periods=mock(RentalPeriodResolver.class);
    RenewalOperationalGuard guard=mock(RenewalOperationalGuard.class);
    RenewalLocalPaymentChecks checks=mock(RenewalLocalPaymentChecks.class);
    @SuppressWarnings("unchecked") ObjectProvider<OverdueSources.RecoverySource> recoveries=mock(ObjectProvider.class);
    LocalDatabaseRenewalWriterProtocol protocol=new LocalDatabaseRenewalWriterProtocol(em,periods,guard,checks,recoveries);
    Rental rental; LocalDate start=LocalDate.of(2026,10,26),end=start.plusMonths(3);
    Instant now=Instant.parse("2026-10-13T06:00:00Z"),cutoff=now.plusSeconds(86400);
    @BeforeEach void setup() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        rental=new Rental();ReflectionTestUtils.setField(rental,"id",UUID.randomUUID());
        var facility=new Facility();ReflectionTestUtils.setField(facility,"id",UUID.randomUUID());rental.setFacility(facility);
        var unit=new StorageUnit();ReflectionTestUtils.setField(unit,"id",UUID.randomUUID());rental.setStorageUnit(unit);
        when(em.find(Rental.class,rental.getId(),LockModeType.PESSIMISTIC_WRITE)).thenReturn(rental);
        when(em.find(StorageUnit.class,unit.getId(),LockModeType.PESSIMISTIC_WRITE)).thenReturn(unit);
        when(periods.require(rental)).thenReturn(new RentalPeriod(rental.getId(),start.minusMonths(3),start.minusDays(1),RentalPeriod.Convention.INCLUSIVE,start.minusDays(1),start,"binding"));
        when(checks.cutoff(rental,now)).thenReturn(cutoff);
    }
    @AfterEach void cleanup(){TransactionSynchronizationManager.clear();}
    @Test void localChecksReuseExistingGuardsAndPublishedCutoffWithoutChangingDates() {
        assertThat(protocol.lockAndCheck(rental,start,end,now)).isEqualTo(cutoff);
        verify(guard).requireNoKnownConflict(rental);
        verify(em).refresh(rental,LockModeType.PESSIMISTIC_WRITE);
        verify(em).refresh(rental.getStorageUnit(),LockModeType.PESSIMISTIC_WRITE);
        verify(em,never()).persist(any());
    }
    @Test void knownOperationalConflictPreventsSigning() {
        doThrow(new IllegalStateException("Return blocks signing")).when(guard).requireNoKnownConflict(rental);
        assertThatThrownBy(()->protocol.lockAndCheck(rental,start,end,now)).hasMessageContaining("Return blocks");
        verify(checks,never()).cutoff(any(),any());
    }
    @Test void availableRecoverySourceCannotBeIgnoredWhenBlockedOrUnknown() {
        var source=mock(OverdueSources.RecoverySource.class);when(recoveries.getIfAvailable()).thenReturn(source);
        when(source.state(rental,now)).thenReturn(Optional.of(new OverdueSources.RecoveryState(rental.getId(),rental.getFacility().getId(),now,true)));
        assertThatThrownBy(()->protocol.lockAndCheck(rental,start,end,now)).hasMessageContaining("Recovery blocks");
        when(source.state(rental,now)).thenReturn(Optional.empty());
        assertThatThrownBy(()->protocol.lockAndCheck(rental,start,end,now)).hasMessageContaining("Recovery state unavailable");
    }
    @Test void internalTaskReceiverChecksItsOwnHandoffsWithoutCertifyingExternalAbsence() {
        var source=mock(RecoveryTaskSource.class);when(recoveries.getIfAvailable()).thenReturn(source);
        assertThat(protocol.lockAndCheck(rental,start,end,now)).isEqualTo(cutoff);
        verify(source).requireNoPersistedHandoff(rental,now);
        verify(source,never()).state(any(),any());
        doThrow(new IllegalStateException("Recovery blocks Renewal signing")).when(source).requireNoPersistedHandoff(rental,now);
        assertThatThrownBy(()->protocol.lockAndCheck(rental,start,end,now)).hasMessageContaining("Recovery blocks");
    }
    @Test void readOnlyOrChangedRentalPeriodCannotAcquireRights() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(()->protocol.lockAndCheck(rental,start,end,now)).hasMessageContaining("writable transaction");
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        assertThatThrownBy(()->protocol.lockAndCheck(rental,start.plusDays(1),end,now)).hasMessageContaining("interval changed");
    }
}
