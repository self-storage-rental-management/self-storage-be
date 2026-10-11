package com.storagehub.service.renewal.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.storagehub.domain.model.*;
import com.storagehub.service.overdue.OverdueSources;
import com.storagehub.service.renewal.operations.RenewalOperationSources;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

/** All providers below are TEST-ONLY; no fake Recovery or calendar is installed at runtime. */
class SharedRenewalEligibilityEvidenceSourceTests {
    static final Instant NOW=Instant.parse("2026-10-11T00:00:00Z"),CUTOFF=NOW.plusSeconds(86400);
    Rental rental;OverdueSources.RecoverySource recovery;OverdueSources.TermSource terms;
    RenewalOperationSources.CalendarSource calendar;SharedRenewalEligibilityEvidenceSource source;StaticListableBeanFactory beans;
    @BeforeEach void setup() {
        rental=new Rental();ReflectionTestUtils.setField(rental,"id",UUID.randomUUID());
        var f=new Facility();ReflectionTestUtils.setField(f,"id",UUID.randomUUID());rental.setFacility(f);
        var u=new StorageUnit();ReflectionTestUtils.setField(u,"id",UUID.randomUUID());rental.setStorageUnit(u);
        recovery=mock(OverdueSources.RecoverySource.class);calendar=mock(RenewalOperationSources.CalendarSource.class);terms=mock(OverdueSources.TermSource.class);
        when(recovery.state(rental,NOW)).thenReturn(Optional.of(new OverdueSources.RecoveryState(rental.getId(),f.getId(),NOW,false)));
        when(calendar.atomic()).thenReturn(true);when(calendar.feasible(rental,NOW,CUTOFF)).thenReturn(Optional.of(true));
        when(terms.term(rental,NOW)).thenReturn(Optional.of(new OverdueSources.Term("TEST","v1",LocalDate.of(2026,10,31),1,3,7,8,CUTOFF,CUTOFF.plusSeconds(1))));
        beans=new StaticListableBeanFactory();beans.addBean("recovery",recovery);beans.addBean("calendar",calendar);beans.addBean("terms",terms);
        source=new SharedRenewalEligibilityEvidenceSource(beans.getBeanProvider(OverdueSources.RecoverySource.class),beans.getBeanProvider(RenewalOperationSources.CalendarSource.class),beans.getBeanProvider(OverdueSources.TermSource.class));
    }
    @Test void combinesReceiverStateAndActualRentalCalendarWithinCurrentCutoff() {
        var e=source.read(rental,NOW).orElseThrow();
        assertThat(e).isEqualTo(new RenewalEligibilityEvidenceSource.Evidence(rental.getId(),rental.getStorageUnit().getId(),rental.getFacility().getId(),NOW,false,true));
        verify(calendar).feasible(rental,NOW,CUTOFF);
    }
    @Test void realRecoveryBlockRemainsBlockedEvenWithAvailableSigningSlot() {
        when(recovery.state(rental,NOW)).thenReturn(Optional.of(new OverdueSources.RecoveryState(rental.getId(),rental.getFacility().getId(),NOW,true)));
        assertThat(source.read(rental,NOW).orElseThrow().recoveryBlocked()).isTrue();
    }
    @Test void noSlotIsVerifiedInfeasibleNotUnknownOrApproved() {
        when(calendar.feasible(rental,NOW,CUTOFF)).thenReturn(Optional.of(false));
        assertThat(source.read(rental,NOW).orElseThrow().feasible()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"RECOVERY","CALENDAR","TERM","NON_ATOMIC"})
    void incompleteAuthoritativeCoverageRemainsUnknown(String kind) {
        if(kind.equals("RECOVERY"))when(recovery.state(rental,NOW)).thenReturn(Optional.empty());
        if(kind.equals("CALENDAR"))when(calendar.feasible(rental,NOW,CUTOFF)).thenReturn(Optional.empty());
        if(kind.equals("TERM"))when(terms.term(rental,NOW)).thenReturn(Optional.empty());
        if(kind.equals("NON_ATOMIC"))when(calendar.atomic()).thenReturn(false);
        assertThat(source.read(rental,NOW)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"RENTAL","FACILITY","STALE","FUTURE"})
    void rejectsForeignOrStaleReceiverEvidence(String kind) {
        when(recovery.state(rental,NOW)).thenReturn(Optional.of(new OverdueSources.RecoveryState(kind.equals("RENTAL")?UUID.randomUUID():rental.getId(),
            kind.equals("FACILITY")?UUID.randomUUID():rental.getFacility().getId(),kind.equals("STALE")?NOW.minusSeconds(1):kind.equals("FUTURE")?NOW.plusSeconds(1):NOW,false)));
        assertThatThrownBy(()->source.read(rental,NOW)).hasMessageContaining("stale or belongs");
        verify(calendar,never()).feasible(any(Rental.class),any(),any());
    }
    @Test void receiverFailureIsNotConvertedToClearRecovery() {
        when(recovery.state(rental,NOW)).thenThrow(new IllegalStateException("Receiver unavailable"));
        assertThatThrownBy(()->source.read(rental,NOW)).hasMessageContaining("Receiver unavailable");
    }
    @Test void backwardCompatibleDefaultsAreUnknown() {
        var oldRecovery=new OverdueSources.RecoverySource(){public OverdueSources.Handoff receive(Rental r,String ref,String reason,String key,Instant at){throw new UnsupportedOperationException();}};
        assertThat(oldRecovery.state(rental,NOW)).isEmpty();
        assertThat(source.read(null,NOW)).isEmpty();
    }
    @Test void holdOptInWithoutSharedProtocolDoesNotAdvertiseAtomicApproval() {
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withBean(jakarta.persistence.EntityManager.class,()->mock(jakarta.persistence.EntityManager.class))
            .withBean(org.springframework.jdbc.core.JdbcTemplate.class,()->new org.springframework.jdbc.core.JdbcTemplate(mock(javax.sql.DataSource.class)))
            .withBean(com.storagehub.service.rental.period.RentalPeriodResolver.class,()->mock(com.storagehub.service.rental.period.RentalPeriodResolver.class))
            .withBean(RenewalOperationalGuard.class,()->mock(RenewalOperationalGuard.class))
            .withUserConfiguration(TransactionalRenewalExtensionHoldSource.class);
        runner.run(ctx->assertThat(ctx).doesNotHaveBean(com.storagehub.service.renewal.RenewalSources.ExtensionHoldSource.class));
        runner.withPropertyValues("storagehub.integration.renewal-extension-holds.enabled=true").run(ctx->assertThat(ctx).doesNotHaveBean(com.storagehub.service.renewal.RenewalSources.ExtensionHoldSource.class));
        runner.withPropertyValues("storagehub.integration.renewal-extension-holds.enabled=true","storagehub.integration.renewal-operational-checks.enabled=true")
            .run(ctx->{var hold=ctx.getBean(TransactionalRenewalExtensionHoldSource.class);assertThat(hold.atomic()).isFalse();assertThat(hold.participatesInTransaction()).isFalse();});
    }
}
