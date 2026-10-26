package com.storagehub.service.renewal.integration;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.StorageUnit;
import com.storagehub.service.overdue.OverdueSources;
import com.storagehub.service.overdue.RecoveryTaskSource;
import com.storagehub.service.rental.period.RentalPeriodResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local Payment rollout only: existing DB conflicts and published cutoff, not external Recovery coverage. */
@Component
@Profile("shared-payment-schema")
@RequiredArgsConstructor
@ConditionalOnProperty(name={"storagehub.integration.renewal-simple-payment.enabled",
    "storagehub.integration.renewal-extension-holds.enabled",
    "storagehub.integration.renewal-operational-checks.enabled"},havingValue="true")
@Transactional(propagation=Propagation.MANDATORY)
public class LocalDatabaseRenewalWriterProtocol implements RenewalSharedWriterProtocol {
    private final EntityManager em;
    private final RentalPeriodResolver periods;
    private final RenewalOperationalGuard guard;
    private final RenewalLocalPaymentChecks checks;
    private final ObjectProvider<OverdueSources.RecoverySource> recoveries;

    @Override @Transactional(propagation=Propagation.SUPPORTS,readOnly=true)
    public boolean ready() { return true; }

    @Override public Instant lockAndCheck(Rental rental,LocalDate start,LocalDate end,Instant now) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()
            ||TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw ApiExceptions.conflict("Renewal operational checks require a writable transaction");
        if(rental==null||rental.getId()==null||now==null)
            throw ApiExceptions.conflict("Renewal operational context missing");
        var locked=em.find(Rental.class,rental.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(locked==null)throw ApiExceptions.notFound("Renewal Rental not found");
        em.refresh(locked,LockModeType.PESSIMISTIC_WRITE);
        if(locked.getStorageUnit()==null)throw ApiExceptions.conflict("Renewal unit missing");
        var unit=em.find(StorageUnit.class,locked.getStorageUnit().getId(),LockModeType.PESSIMISTIC_WRITE);
        if(unit==null)throw ApiExceptions.conflict("Renewal unit missing");
        em.refresh(unit,LockModeType.PESSIMISTIC_WRITE);
        var previous=periods.require(locked);
        if(start==null||end==null||!end.isAfter(start)||start.isBefore(previous.endExclusive())
            ||start.isAfter(com.storagehub.service.rental.period.RenewalEffectivePeriod.start(previous,now)))
            throw ApiExceptions.conflict("Renewal extension interval changed");
        guard.requireNoKnownConflict(locked);
        var recovery=recoveries.getIfAvailable();
        if(recovery instanceof RecoveryTaskSource tasks) {
            // A local task receiver certifies only its own persisted handoffs. The shared Rental lock,
            // operational guard and published cutoff still apply; absence is not external coverage.
            tasks.requireNoPersistedHandoff(locked,now);
        } else if(recovery!=null) {
            var state=recovery.state(locked,now).orElseThrow(()->ApiExceptions.conflict("DEFERRED_SOURCE: Recovery state unavailable"));
            if(!Objects.equals(state.rentalId(),locked.getId())
                ||!Objects.equals(state.facilityId(),locked.getFacility().getId())
                ||state.checkedAt()==null||state.checkedAt().isAfter(now))
                throw ApiExceptions.conflict("DEFERRED_SOURCE: Recovery state binding invalid");
            if(state.recoveryBlocked())throw ApiExceptions.conflict("Recovery blocks Renewal signing");
        }
        return checks.cutoff(locked,now);
    }
}
