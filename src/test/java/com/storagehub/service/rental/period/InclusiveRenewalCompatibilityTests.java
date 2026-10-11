package com.storagehub.service.rental.period;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.storagehub.api.renewal.RenewalQuoteResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InclusiveRenewalCompatibilityTests {
    private final LocalDate start=LocalDate.of(2026,10,10),expiry=LocalDate.of(2027,1,10);
    private RentalPeriod period() {
        return RentalPeriod.verified(UUID.randomUUID(),start,expiry,RentalPeriod.Convention.INCLUSIVE,"project-inclusive-end:v1:test").orElseThrow();
    }
    private RenewalQuoteResponse.Terms terms(String convention,LocalDate extensionStart) {
        var terms=mock(RenewalQuoteResponse.Terms.class);
        when(terms.endDateConvention()).thenReturn(convention);when(terms.rentalStartDate()).thenReturn(start);
        when(terms.oldEndDate()).thenReturn(expiry);when(terms.extensionStartDate()).thenReturn(extensionStart);
        when(terms.rentalMonths()).thenReturn(3);when(terms.extensionEndExclusive()).thenReturn(LocalDate.of(2027,4,11));
        when(terms.newEndDate()).thenReturn(LocalDate.of(2027,4,10));return terms;
    }
    @Test void renewalBeginsAfterFullExpiryDayAndEndsAfterFullNewExpiryDay() {
        var p=period();assertThat(p.lastPermittedDate()).isEqualTo(expiry);
        assertThat(p.endExclusive()).isEqualTo(LocalDate.of(2027,1,11));
        assertThatCode(()->RenewalPeriodCompatibility.require(p,terms("INCLUSIVE",p.endExclusive()))).doesNotThrowAnyException();
    }
    @Test void oldAcceptedExclusiveQuoteIsBlockedForRequoteNotSilentlyExtendedOrCharged() {
        assertThatThrownBy(()->RenewalPeriodCompatibility.require(period(),terms("EXCLUSIVE",expiry)))
            .hasMessageContaining("requote required");
    }
}
