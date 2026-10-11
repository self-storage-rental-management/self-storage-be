package com.storagehub.service.renewal.integration;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Rental;
import com.storagehub.service.overdue.OverdueSources;
import com.storagehub.service.renewal.operations.RenewalOperationSources;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Composite runtime adapter. Absence of handoffs/bookings is never positive evidence. */
@Component @RequiredArgsConstructor @Transactional(readOnly=true)
@ConditionalOnProperty(name="storagehub.integration.renewal-operational-checks.enabled",havingValue="true")
public class SharedRenewalEligibilityEvidenceSource implements RenewalEligibilityEvidenceSource {
    private final ObjectProvider<OverdueSources.RecoverySource> recoveries;
    private final ObjectProvider<RenewalOperationSources.CalendarSource> calendars;
    private final ObjectProvider<OverdueSources.TermSource> terms;

    @Override public Optional<Evidence> read(Rental rental,Instant now) {
        if(rental==null||rental.getId()==null||rental.getFacility()==null||rental.getStorageUnit()==null||now==null)return Optional.empty();
        var recovery=recoveries.getIfAvailable();var calendar=calendars.getIfAvailable();var termSource=terms.getIfAvailable();
        if(recovery==null||calendar==null||!calendar.atomic()||termSource==null)return Optional.empty();
        var state=recovery.state(rental,now);var term=termSource.term(rental,now);
        if(state.isEmpty()||term.isEmpty())return Optional.empty();
        var s=state.get();var cutoff=term.get().recoveryCutoff();
        if(!Objects.equals(s.rentalId(),rental.getId())||!Objects.equals(s.facilityId(),rental.getFacility().getId())||!Objects.equals(s.checkedAt(),now))
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Recovery evidence is stale or belongs to another rental");
        if(cutoff==null||!now.isBefore(cutoff))return Optional.empty();
        return calendar.feasible(rental,now,cutoff).map(feasible->new Evidence(rental.getId(),rental.getStorageUnit().getId(),
            rental.getFacility().getId(),now,s.recoveryBlocked(),feasible));
    }
}
