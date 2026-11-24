package com.storagehub.service.ledger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
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
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(rental,"id",UUID.randomUUID());ReflectionTestUtils.setField(renewal,"id",UUID.randomUUID());
        var facility=new Facility();ReflectionTestUtils.setField(facility,"id",UUID.randomUUID());rental.setFacility(facility);renewal.setRental(rental);
        when(periods.require(rental)).thenReturn(RentalPeriod.verified(rental.getId(),LocalDate.of(2026,7,10),LocalDate.of(2026,10,10),RentalPeriod.Convention.INCLUSIVE,"period").orElseThrow());
        rental.setMonthlyPrice(new BigDecimal("12000000"));
    }
    private LedgerStore.Snapshot snapshot(List<LedgerStore.Obligation> obligations) {return new LedgerStore.Snapshot(rental.getId(),UUID.randomUUID(),rental.getFacility().getId(),1,obligations,List.of(),List.of());}
    @Test void firstOverdueDayBeginsAtFacilityMidnight() {
        assertThat(fees.estimate(rental,Instant.parse("2026-10-10T16:59:59Z"))).isZero();
        assertThat(fees.estimate(rental,Instant.parse("2026-10-10T17:00:00Z"))).isEqualByComparingTo("600000");
        assertThat(fees.estimate(rental,Instant.parse("2026-10-12T16:59:59Z"))).isEqualByComparingTo("1200000");
        assertThat(fees.quote(renewal,snapshot(List.of()),Instant.parse("2026-10-10T17:00:00Z")).nextChangeAt()).isEqualTo(Instant.parse("2026-10-11T17:00:00Z"));
    }
    @Test void paidFeesAreNotChargedAgainAndOnlyTheNewDayAccrues() {
        var first=fees.quote(renewal,snapshot(List.of()),Instant.parse("2026-10-12T01:00:00Z"));
        var paid=new LedgerStore.Obligation(UUID.randomUUID(),renewal.getId(),LedgerStore.Kind.RENEWAL_REMAINDER,new BigDecimal("600000"),first.dueAt(),first.sourceRef(),new BigDecimal("600000"),BigDecimal.ZERO,BigDecimal.ZERO);
        var sameDay=fees.quote(renewal,snapshot(List.of(paid)),Instant.parse("2026-10-12T09:00:00Z"));
        assertThat(sameDay.outstanding()).isZero();assertThat(sameDay.unposted()).isZero();
        var nextDay=fees.quote(renewal,snapshot(List.of(paid)),Instant.parse("2026-10-12T17:00:00Z"));
        assertThat(nextDay.accrued()).isEqualByComparingTo("1200000");assertThat(nextDay.outstanding()).isEqualByComparingTo("600000");
    }
    @Test void aMissingRateCannotBecomeTheHardcodedGlobalDefault() {
        rental.setMonthlyPrice(null);
        assertThat(fees.estimate(rental,Instant.parse("2026-10-10T16:59:59Z"))).isZero();
        assertThatThrownBy(()->fees.estimate(rental,Instant.parse("2026-10-11T01:00:00Z"))).hasMessageContaining("Monthly rental price");
    }
    @Test void changingTheRateDoesNotSilentlyRepricePreviouslyPostedFees() {
        var first=fees.quote(renewal,snapshot(List.of()),Instant.parse("2026-10-12T01:00:00Z"));
        var posted=new LedgerStore.Obligation(UUID.randomUUID(),renewal.getId(),LedgerStore.Kind.RENEWAL_REMAINDER,new BigDecimal("600000"),first.dueAt(),first.sourceRef(),BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("600000"));
        rental.setMonthlyPrice(new BigDecimal("14000000"));
        assertThatThrownBy(()->fees.quote(renewal,snapshot(List.of(posted)),Instant.parse("2026-10-13T01:00:00Z"))).hasMessageContaining("policy changed");
    }
    @Test void negativeOrUnsupportedMonthlyPricesAreRejected() {
        rental.setMonthlyPrice(BigDecimal.ONE.negate());assertThatThrownBy(()->fees.estimate(rental,Instant.parse("2026-10-11T01:00:00Z"))).hasMessageContaining("invalid");
        rental.setMonthlyPrice(new BigDecimal("99999999999999"));assertThatThrownBy(()->fees.estimate(rental,Instant.parse("2026-10-11T01:00:00Z"))).hasMessageContaining("unsupported");
    }
    @Test void aNewRenewalForTheSameRentalPeriodDoesNotCollectPreviouslyPaidDailyFeesAgain() {
        var first=fees.quote(renewal,snapshot(List.of()),Instant.parse("2026-10-12T01:00:00Z"));
        var paid=new LedgerStore.Obligation(UUID.randomUUID(),renewal.getId(),LedgerStore.Kind.RENEWAL_REMAINDER,new BigDecimal("600000"),first.dueAt(),first.sourceRef(),new BigDecimal("600000"),BigDecimal.ZERO,BigDecimal.ZERO);
        var next=new Renewal();next.setRental(rental);ReflectionTestUtils.setField(next,"id",UUID.randomUUID());
        assertThat(fees.quote(next,snapshot(List.of(paid)),Instant.parse("2026-10-12T09:00:00Z")).outstanding()).isZero();
    }
    @Test void overdueFeeCalculatedFromMonthlyPriceMultipliedByOnePointFive() {
        // monthlyPrice = 3.000.000 -> 1 ngày = 100.000 -> mỗi ngày quá hạn nhân 1.5 = 150.000
        rental.setMonthlyPrice(new BigDecimal("3000000"));
        assertThat(fees.estimate(rental, Instant.parse("2026-10-10T17:00:00Z"))).isEqualByComparingTo("150000");
        assertThat(fees.estimate(rental, Instant.parse("2026-10-12T16:59:59Z"))).isEqualByComparingTo("300000");
    }
}
