package com.storagehub.service.renewal.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.PublishedRenewalPolicy;
import com.storagehub.domain.model.*;
import com.storagehub.service.rental.period.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

class PublishedRenewalPolicyDateTests {
    Rental rental;RenewalPolicyPublicationService policies;PublishedRenewalPolicyAdapters adapters;
    PublishedRenewalPolicy future;RentalPeriod period;
    @BeforeEach void setup() {
        rental=new Rental();ReflectionTestUtils.setField(rental,"id",UUID.randomUUID());
        var f=new Facility();ReflectionTestUtils.setField(f,"id",UUID.randomUUID());rental.setFacility(f);
        rental.setStartDate(LocalDate.of(2026,10,1));rental.setContractEndDate(LocalDate.of(2026,10,31));
        period=RentalPeriod.verified(rental.getId(),rental.getStartDate(),rental.getContractEndDate(),RentalPeriod.Convention.INCLUSIVE,"TEST").orElseThrow();
        future=new PublishedRenewalPolicy("RENEWAL_POLICY_V1",f.getId(),1,"future",UUID.randomUUID(),Instant.parse("2026-10-01T00:00:00Z"),
            LocalDate.of(2026,11,1),LocalDate.of(2026,11,30),30,24,30,BigDecimal.ZERO,Set.of(),
            new PublishedRenewalPolicy.Signing(60,60),new PublishedRenewalPolicy.Term("CALENDAR_DAYS","Asia/Ho_Chi_Minh",1,3,7,8,LocalTime.of(17,0),LocalTime.of(8,0)));
        policies=mock(RenewalPolicyPublicationService.class);
        when(policies.read(eq(f.getId()),any())).thenAnswer(i->RenewalPolicyPublicationService.appliesOn(future,i.getArgument(1))?Optional.of(new RenewalPolicyPublicationService.Stored("TEST",future)):Optional.empty());
        var periods=mock(RentalPeriodResolver.class);when(periods.read(rental)).thenReturn(Optional.of(period));
        adapters=new PublishedRenewalPolicyAdapters(policies,periods,null,new ObjectMapper(),null);
    }
    @ParameterizedTest @CsvSource({"2026-10-31,false","2026-11-01,true","2026-11-30,true","2026-12-01,false"})
    void effectiveIntervalIncludesBothBoundaries(String date,boolean expected) {
        assertThat(RenewalPolicyPublicationService.appliesOn(future,LocalDate.parse(date))).isEqualTo(expected);
    }
    @Test void futureExtensionPolicyDoesNotBecomeTodaysOverduePolicy() {
        assertThat(adapters.read(rental,period.endExclusive())).isPresent();
        var n=new Renewal();n.setRental(rental);assertThat(adapters.read(n)).isPresent();
        assertThat(adapters.term(rental,Instant.parse("2026-10-31T12:00:00Z"))).isEmpty();
        verify(policies,times(2)).read(rental.getFacility().getId(),LocalDate.of(2026,11,1));
        verify(policies).read(rental.getFacility().getId(),LocalDate.of(2026,10,31));
    }
    @Test void overdueAppliesCurrentVietnamDateNotUtcOrExtensionDate() {
        assertThat(adapters.term(rental,Instant.parse("2026-10-31T17:00:00Z"))).isPresent();
        assertThat(adapters.term(rental,Instant.parse("2026-11-30T16:59:59Z"))).isPresent();
        assertThat(adapters.term(rental,Instant.parse("2026-11-30T17:00:00Z"))).isEmpty();
    }
    @Test void unboundedPolicyAndReadbackKeepExistingSemantics() {
        var unbounded=new PublishedRenewalPolicy(future.schema(),future.facilityId(),1,"v",future.publishedBy(),future.publishedAt(),
            future.effectiveFrom(),null,30,24,30,BigDecimal.ZERO,Set.of(),future.signing(),future.term());
        assertThat(RenewalPolicyPublicationService.appliesOn(unbounded,LocalDate.of(2030,1,1))).isTrue();
        assertThat(RenewalPolicyPublicationService.appliesOn(unbounded,null)).isTrue();
    }
}
