package com.storagehub.service.renewal.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.service.overdue.OverdueSources;
import com.storagehub.service.rental.period.*;
import com.storagehub.service.renewal.RenewalSources;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Test-only evidence, never runtime sources or policy seeds. */
class SharedRenewalEligibilitySourceTests {
    static final Instant NOW=Instant.parse("2026-10-11T00:00:00Z");
    Rental rental;RentalPeriod period;RenewalOperationalGuard guard;RentalPeriodResolver periods;
    StaticListableBeanFactory factory;SharedRenewalEligibilitySource source;
    OverdueSources.Term term;Optional<RenewalEligibilityEvidenceSource.Evidence> evidence;
    @BeforeEach void fixture(){
        rental=new Rental();org.springframework.test.util.ReflectionTestUtils.setField(rental,"id",UUID.randomUUID());rental.setStartDate(LocalDate.of(2026,10,1));rental.setContractEndDate(LocalDate.of(2026,10,31));
        var facility=new Facility();org.springframework.test.util.ReflectionTestUtils.setField(facility,"id",UUID.randomUUID());rental.setFacility(facility);
        var unit=new StorageUnit();org.springframework.test.util.ReflectionTestUtils.setField(unit,"id",UUID.randomUUID());rental.setStorageUnit(unit);
        period=RentalPeriod.verified(rental.getId(),rental.getStartDate(),rental.getContractEndDate(),RentalPeriod.Convention.INCLUSIVE,"TEST-only").orElseThrow();
        guard=mock(RenewalOperationalGuard.class);periods=mock(RentalPeriodResolver.class);when(periods.read(rental)).thenReturn(Optional.of(period));
        term=new OverdueSources.Term("TEST-BO","v1",period.lastPermittedDate(),1,3,7,8,NOW.plusSeconds(86400),NOW.plusSeconds(90000));
        evidence=Optional.of(new RenewalEligibilityEvidenceSource.Evidence(rental.getId(),unit.getId(),facility.getId(),NOW,false,true));
        factory=new StaticListableBeanFactory();factory.addBean("terms",(OverdueSources.TermSource)(r,n)->Optional.ofNullable(term));
        factory.addBean("coverage",(RenewalEligibilityEvidenceSource)(r,n)->evidence);
        source=adapter(factory);
    }
    SharedRenewalEligibilitySource adapter(StaticListableBeanFactory beans){return new SharedRenewalEligibilitySource(guard,periods,beans.getBeanProvider(OverdueSources.TermSource.class),beans.getBeanProvider(RenewalEligibilityEvidenceSource.class));}
    @Test void completeBoundEvidenceUsesVerifiedDatesAndPublishedCutoff(){
        assertThat(source.read(rental,NOW)).contains(new RenewalSources.Eligibility(true,false,true,term.recoveryCutoff()));
        verify(guard).requireNoKnownConflict(rental);
    }
    @Test void noConflictDoesNotReplaceMissingRecoveryCalendarCoverage(){
        var beans=new StaticListableBeanFactory();beans.addBean("terms",factory.getBean("terms"));
        assertThat(adapter(beans).read(rental,NOW)).isEmpty();
        evidence=Optional.empty();assertThat(source.read(rental,NOW)).isEmpty();
    }
    @Test void missingTermOrDateOrInstantStaysUnknown(){
        term=null;assertThat(source.read(rental,NOW)).isEmpty();
        when(periods.read(rental)).thenReturn(Optional.empty());assertThat(source.read(rental,NOW)).isEmpty();
        assertThat(source.read(rental,null)).isEmpty();
        assertThat(adapter(new StaticListableBeanFactory()).read(rental,NOW)).isEmpty();
    }
    @Test void definitiveRecoveryAndUnfeasibleEvidenceAreNotAvailable(){
        evidence=Optional.of(new RenewalEligibilityEvidenceSource.Evidence(rental.getId(),rental.getStorageUnit().getId(),rental.getFacility().getId(),NOW,true,false));
        assertThat(source.read(rental,NOW)).contains(new RenewalSources.Eligibility(true,true,false,term.recoveryCutoff()));
    }
    @Test void cutoffEqualityBlocksEvenWithoutPositiveCoverage(){
        assertThat(source.read(rental,term.recoveryCutoff()).orElseThrow().feasible()).isFalse();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"RENTAL","UNIT","FACILITY","STALE","FUTURE"})
    void mismatchedOrNotCurrentEvidenceCannotProveEligibility(String kind){
        evidence=Optional.of(new RenewalEligibilityEvidenceSource.Evidence(kind.equals("RENTAL")?UUID.randomUUID():rental.getId(),kind.equals("UNIT")?UUID.randomUUID():rental.getStorageUnit().getId(),kind.equals("FACILITY")?UUID.randomUUID():rental.getFacility().getId(),kind.equals("STALE")?NOW.minusSeconds(1):kind.equals("FUTURE")?NOW.plusSeconds(1):NOW,false,true));
        assertThatThrownBy(()->source.read(rental,NOW)).hasMessageContaining("stale or belongs");
    }
    @Test void inconsistentPolicyIsBlockedNotSilentlyUnknown(){
        term=new OverdueSources.Term("TEST","v1",period.lastPermittedDate().plusDays(1),1,3,7,8,NOW.plusSeconds(100),NOW.plusSeconds(200));
        assertThatThrownBy(()->source.read(rental,NOW)).hasMessageContaining("mapping inconsistent");
    }
    @Test void guardAndInfrastructureExceptionsPropagate(){
        doThrow(ApiExceptions.conflict("Recovery handoff blocks renewal")).when(guard).requireNoKnownConflict(rental);
        assertThatThrownBy(()->source.read(rental,NOW)).hasMessageContaining("Recovery handoff");verifyNoInteractions(periods);
        reset(guard);when(periods.read(rental)).thenThrow(new IllegalStateException("DB unavailable"));
        assertThatThrownBy(()->source.read(rental,NOW)).hasMessageContaining("DB unavailable");
    }
    @Test void optInRegistersEligibilityButDoesNotInventCoverageOrApprovalSources(){
        new ApplicationContextRunner().withBean(RenewalOperationalGuard.class,()->guard).withBean(RentalPeriodResolver.class,()->periods)
            .withUserConfiguration(SharedRenewalEligibilitySource.class).run(ctx->assertThat(ctx).doesNotHaveBean(RenewalSources.EligibilitySource.class));
        new ApplicationContextRunner().withBean(RenewalOperationalGuard.class,()->guard).withBean(RentalPeriodResolver.class,()->periods)
            .withUserConfiguration(SharedRenewalEligibilitySource.class).withPropertyValues("storagehub.integration.renewal-operational-checks.enabled=true")
            .run(ctx->{assertThat(ctx).hasSingleBean(RenewalSources.EligibilitySource.class);assertThat(ctx).doesNotHaveBean(RenewalEligibilityEvidenceSource.class);assertThat(ctx).doesNotHaveBean(RenewalSources.ExtensionHoldSource.class);assertThat(ctx).doesNotHaveBean(RenewalSources.ApprovalLifecycleSource.class);});
    }
}
