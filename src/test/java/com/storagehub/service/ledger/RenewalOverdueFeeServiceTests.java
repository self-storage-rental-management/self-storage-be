package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.storagehub.api.renewal.PublishedRenewalPolicy;
import com.storagehub.domain.model.*;
import com.storagehub.service.rental.period.*;
import com.storagehub.service.renewal.integration.RenewalPolicyPublicationService;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class RenewalOverdueFeeServiceTests {
    private final EntityManager em=mock(EntityManager.class);
    private final RentalPeriodResolver periods=mock(RentalPeriodResolver.class);
    private final RenewalPolicyPublicationService policies=mock(RenewalPolicyPublicationService.class);
    private final RenewalOverdueFeeService fees=new RenewalOverdueFeeService(em,periods,policies);
    private final Rental rental=new Rental();private final Renewal renewal=new Renewal();
    private final SystemSetting setting=new SystemSetting();
    private TypedQuery<SystemSetting> settings;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        ReflectionTestUtils.setField(rental,"id",UUID.randomUUID());ReflectionTestUtils.setField(renewal,"id",UUID.randomUUID());
        var facility=new Facility();ReflectionTestUtils.setField(facility,"id",UUID.randomUUID());rental.setFacility(facility);renewal.setRental(rental);
        when(periods.require(rental)).thenReturn(RentalPeriod.verified(rental.getId(),LocalDate.of(2026,7,10),LocalDate.of(2026,10,10),RentalPeriod.Convention.INCLUSIVE,"period").orElseThrow());
        var policy=mock(PublishedRenewalPolicy.class);
        when(policy.term()).thenReturn(new PublishedRenewalPolicy.Term("calendar","Asia/Ho_Chi_Minh",3,6,7,8,LocalTime.of(17,0),LocalTime.MIDNIGHT));
        when(policies.read(eq(facility.getId()),any())).thenReturn(Optional.of(new RenewalPolicyPublicationService.Stored("setting:policy",policy)));
        settings=mock(TypedQuery.class,RETURNS_SELF);setting.setValue("650000");when(settings.getResultList()).thenReturn(List.of(setting));
        when(em.createQuery(anyString(),eq(SystemSetting.class))).thenReturn(settings);
    }
    private LedgerStore.Snapshot snapshot(List<LedgerStore.Obligation> obligations) {return new LedgerStore.Snapshot(rental.getId(),UUID.randomUUID(),rental.getFacility().getId(),1,obligations,List.of(),List.of());}
    @Test void firstOverdueDayBeginsAtFacilityMidnight() {
        assertThat(fees.estimate(rental,Instant.parse("2026-10-10T16:59:59Z"))).isZero();
        assertThat(fees.estimate(rental,Instant.parse("2026-10-10T17:00:00Z"))).isEqualByComparingTo("650000");
        assertThat(fees.estimate(rental,Instant.parse("2026-10-12T16:59:59Z"))).isEqualByComparingTo("1300000");
        assertThat(fees.quote(renewal,snapshot(List.of()),Instant.parse("2026-10-10T17:00:00Z")).nextChangeAt()).isEqualTo(Instant.parse("2026-10-11T17:00:00Z"));
    }
    @Test void paidFeesAreNotChargedAgainAndOnlyTheNewDayAccrues() {
        var first=fees.quote(renewal,snapshot(List.of()),Instant.parse("2026-10-11T01:00:00Z"));
        var paid=new LedgerStore.Obligation(UUID.randomUUID(),renewal.getId(),LedgerStore.Kind.RENEWAL_REMAINDER,new BigDecimal("650000"),first.dueAt(),first.sourceRef(),new BigDecimal("650000"),BigDecimal.ZERO,BigDecimal.ZERO);
        var sameDay=fees.quote(renewal,snapshot(List.of(paid)),Instant.parse("2026-10-11T09:00:00Z"));
        assertThat(sameDay.outstanding()).isZero();assertThat(sameDay.unposted()).isZero();
        var nextDay=fees.quote(renewal,snapshot(List.of(paid)),Instant.parse("2026-10-11T17:00:00Z"));
        assertThat(nextDay.accrued()).isEqualByComparingTo("1300000");assertThat(nextDay.outstanding()).isEqualByComparingTo("650000");
    }
    @Test void aMissingRateCannotBecomeTheHardcodedGlobalDefault() {
        when(settings.getResultList()).thenReturn(List.of());
        assertThat(fees.estimate(rental,Instant.parse("2026-10-10T16:59:59Z"))).isZero();
        assertThatThrownBy(()->fees.estimate(rental,Instant.parse("2026-10-11T01:00:00Z"))).hasMessageContaining("setting missing");
    }
    @Test void changingTheRateDoesNotSilentlyRepricePreviouslyPostedFees() {
        var first=fees.quote(renewal,snapshot(List.of()),Instant.parse("2026-10-11T01:00:00Z"));
        var posted=new LedgerStore.Obligation(UUID.randomUUID(),renewal.getId(),LedgerStore.Kind.RENEWAL_REMAINDER,new BigDecimal("650000"),first.dueAt(),first.sourceRef(),BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("650000"));
        setting.setValue("700000");
        assertThatThrownBy(()->fees.quote(renewal,snapshot(List.of(posted)),Instant.parse("2026-10-12T01:00:00Z"))).hasMessageContaining("policy changed");
    }
    @Test void negativeOrFractionalVndRatesAreRejected() {
        setting.setValue("-1");assertThatThrownBy(()->fees.estimate(rental,Instant.parse("2026-10-11T01:00:00Z"))).hasMessageContaining("unsupported");
        setting.setValue("1.5");assertThatThrownBy(()->fees.estimate(rental,Instant.parse("2026-10-11T01:00:00Z"))).hasMessageContaining("invalid");
    }
    @Test void aNewRenewalForTheSameRentalPeriodDoesNotCollectPreviouslyPaidDailyFeesAgain() {
        var first=fees.quote(renewal,snapshot(List.of()),Instant.parse("2026-10-11T01:00:00Z"));
        var paid=new LedgerStore.Obligation(UUID.randomUUID(),renewal.getId(),LedgerStore.Kind.RENEWAL_REMAINDER,new BigDecimal("650000"),first.dueAt(),first.sourceRef(),new BigDecimal("650000"),BigDecimal.ZERO,BigDecimal.ZERO);
        var next=new Renewal();next.setRental(rental);ReflectionTestUtils.setField(next,"id",UUID.randomUUID());
        assertThat(fees.quote(next,snapshot(List.of(paid)),Instant.parse("2026-10-11T09:00:00Z")).outstanding()).isZero();
    }
}
