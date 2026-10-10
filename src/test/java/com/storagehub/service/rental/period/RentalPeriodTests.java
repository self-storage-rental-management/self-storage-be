package com.storagehub.service.rental.period;

import static org.assertj.core.api.Assertions.*;
import com.storagehub.domain.model.Rental;
import com.storagehub.service.RentalReadSources;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RentalPeriodTests {
    private final UUID id = UUID.randomUUID();
    private final LocalDate start = LocalDate.of(2026, 10, 1);
    @Test void exclusiveAndInclusiveRepresentTheSameCanonicalPeriodWithoutChangingRaw() {
        var e = RentalPeriod.verified(id, start, LocalDate.of(2026,11,1), RentalPeriod.Convention.EXCLUSIVE,"receipt:proof").orElseThrow();
        var i = RentalPeriod.verified(id, start, LocalDate.of(2026,10,31), RentalPeriod.Convention.INCLUSIVE,"owner:proof").orElseThrow();
        assertThat(e.endExclusive()).isEqualTo(i.endExclusive());
        assertThat(e.lastPermittedDate()).isEqualTo(i.lastPermittedDate());
        assertThat(e.storedEndDate()).isEqualTo(LocalDate.of(2026,11,1));
        assertThat(e.storeEnd(LocalDate.of(2026,12,1))).isEqualTo(LocalDate.of(2026,12,1));
        assertThat(i.storeEnd(LocalDate.of(2026,12,1))).isEqualTo(LocalDate.of(2026,11,30));
    }
    @Test void resolverValidatesRawTupleAndCanonicalDateTogether() {
        var r = new Rental(); ReflectionTestUtils.setField(r,"id",id);
        r.setStartDate(start); r.setContractEndDate(LocalDate.of(2026,11,1));
        var d = new RentalReadSources.Dates(id,start,LocalDate.of(2026,10,31),"receipt:proof",r.getContractEndDate(),RentalPeriod.Convention.EXCLUSIVE);
        assertThat(RentalPeriodResolver.normalize(r,d)).isPresent();
        assertThat(RentalPeriodResolver.normalize(r,new RentalReadSources.Dates(id,start,r.getContractEndDate(),"receipt:proof",r.getContractEndDate(),RentalPeriod.Convention.EXCLUSIVE))).isEmpty();
        r.setContractEndDate(LocalDate.of(2026,11,2));
        assertThat(RentalPeriodResolver.normalize(r,d)).isEmpty();
    }
    @Test void explicitLegacyInclusiveSourceIsCompatibleButNeverInferred() {
        var r = new Rental(); ReflectionTestUtils.setField(r,"id",id);r.setStartDate(start);r.setContractEndDate(start);
        assertThat(RentalPeriodResolver.normalize(r,new RentalReadSources.Dates(id,start,start,"explicit-owner-proof"))).isPresent();
        assertThat(RentalPeriodResolver.normalize(r,new RentalReadSources.Dates(id,start,start,""))).isEmpty();
        assertThat(RentalPeriodResolver.normalize(r,null)).isEmpty();
    }
    @Test void missingInvalidAndOverflowEvidenceRemainsUnknown() {
        assertThat(RentalPeriod.verified(id,start,start,RentalPeriod.Convention.EXCLUSIVE,"proof")).isEmpty();
        assertThat(RentalPeriod.verified(id,start,start.minusDays(1),RentalPeriod.Convention.INCLUSIVE,"proof")).isEmpty();
        assertThat(RentalPeriod.verified(id,start,LocalDate.MAX,RentalPeriod.Convention.INCLUSIVE,"proof")).isEmpty();
        assertThat(RentalPeriod.verified(id,start,start,null,"proof")).isEmpty();
        assertThat(RentalPeriod.verified(null,start,start,RentalPeriod.Convention.INCLUSIVE,"proof")).isEmpty();
    }
    @Test void resolverDoesNotInventProvenanceWhenNoProviderExists() {
        var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var resolver = new RentalPeriodResolver(factory.getBeanProvider(RentalReadSources.DateSource.class));
        assertThat(resolver.read(new Rental())).isEmpty();
        assertThatThrownBy(() -> resolver.require(new Rental())).hasMessageContaining("DEFERRED_SOURCE");
    }
}
