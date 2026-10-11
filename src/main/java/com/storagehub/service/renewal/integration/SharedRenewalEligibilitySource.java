package com.storagehub.service.renewal.integration;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Rental;
import com.storagehub.service.overdue.OverdueSources;
import com.storagehub.service.rental.period.RentalPeriodResolver;
import com.storagehub.service.renewal.RenewalSources;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read-only P05 adapter. Known conflicts block; incomplete positive evidence stays UNKNOWN. */
@Component @RequiredArgsConstructor @Transactional(readOnly=true)
@ConditionalOnProperty(name="storagehub.integration.renewal-operational-checks.enabled", havingValue="true")
public class SharedRenewalEligibilitySource implements RenewalSources.EligibilitySource {
    private final RenewalOperationalGuard guard;
    private final RentalPeriodResolver periods;
    private final ObjectProvider<OverdueSources.TermSource> terms;
    private final ObjectProvider<RenewalEligibilityEvidenceSource> coverage;

    @Override public Optional<RenewalSources.Eligibility> read(Rental rental, Instant now) {
        guard.requireNoKnownConflict(rental);
        if(now==null)return Optional.empty();
        var period=periods.read(rental);
        var termSource=terms.getIfAvailable();
        if(period.isEmpty()||termSource==null)return Optional.empty();
        var term=termSource.term(rental,now);
        if(term.isEmpty())return Optional.empty();
        var t=term.get();
        if(t.policyRef()==null||t.policyRef().isBlank()||t.policyVersion()==null||t.policyVersion().isBlank()
            ||!Objects.equals(t.lastPermittedDate(),period.get().lastPermittedDate())
            ||t.warningThroughDay()<1||t.seriousThroughDay()<=t.warningThroughDay()
            ||t.urgentThroughDay()<=t.seriousThroughDay()||t.recoveryFromDay()!=t.urgentThroughDay()+1
            ||t.recoveryCutoff()==null||t.recoveryStart()==null||!t.recoveryStart().isAfter(t.recoveryCutoff()))
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Eligibility policy/date/cutoff mapping inconsistent");
        // A published cutoff proves expiry, not absence of external Recovery or a signing slot.
        if(!now.isBefore(t.recoveryCutoff()))return Optional.of(new RenewalSources.Eligibility(true,true,false,t.recoveryCutoff()));
        var source=coverage.getIfAvailable();
        if(source==null)return Optional.empty();
        return source.read(rental,now).map(e->{
            if(!Objects.equals(e.rentalId(),rental.getId())||!Objects.equals(e.unitId(),rental.getStorageUnit().getId())
                ||!Objects.equals(e.facilityId(),rental.getFacility().getId())||!Objects.equals(e.checkedAt(),now))
                throw ApiExceptions.conflict("DEFERRED_SOURCE: Eligibility evidence is stale or belongs to another rental");
            return new RenewalSources.Eligibility(true,e.recoveryBlocked(),e.feasible(),t.recoveryCutoff());
        });
    }
}
