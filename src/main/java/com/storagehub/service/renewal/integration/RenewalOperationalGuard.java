package com.storagehub.service.renewal.integration;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Negative evidence only. Passing these checks is NOT proof of recovery/calendar/hold coverage. */
@Component @RequiredArgsConstructor @Transactional(readOnly=true)
@ConditionalOnProperty(name="storagehub.integration.renewal-operational-checks.enabled", havingValue="true")
public class RenewalOperationalGuard {
    private final EntityManager em;

    public void requireNoKnownConflict(Rental rental) {
        if(rental==null || rental.getId()==null || rental.getFacility()==null || rental.getStorageUnit()==null)
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Rental operational context incomplete");
        if(rental.getStatus()!=RentalStatus.active || rental.getActualReturnedAt()!=null || rental.getCompletedAt()!=null)
            throw ApiExceptions.conflict("Return or non-active rental blocks renewal");
        var unit=em.find(StorageUnit.class,rental.getStorageUnit().getId());
        if(unit==null || unit.getFacility()==null || !Objects.equals(unit.getFacility().getId(),rental.getFacility().getId()))
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Rental unit/facility mapping incomplete");
        // Never infer physical availability from a contract end date or modify the shared unit status.
        if(unit.getStatus()!=StorageUnitStatus.occupied)
            throw ApiExceptions.conflict("Rental unit is no longer occupied by the current rental");
        if(!currentQuery("select x from ReturnCase x where x.rental.id=:rental")
            .setParameter("rental",rental.getId()).getResultList().isEmpty())
            throw ApiExceptions.conflict("Existing return case blocks renewal");
        if(!currentQuery("select x from MaintenanceTask x where x.storageUnit.id=:unit and x.status in :statuses")
            .setParameter("unit",unit.getId()).setParameter("statuses",List.of(MaintenanceTaskStatus.open,MaintenanceTaskStatus.in_progress)).getResultList().isEmpty())
            throw ApiExceptions.conflict("Active maintenance blocks renewal");
        if(!currentQuery("select x from Rental x where x.storageUnit.id=:unit and x.id<>:rental and x.status<>:completed")
            .setParameter("unit",unit.getId()).setParameter("rental",rental.getId()).setParameter("completed",RentalStatus.completed).getResultList().isEmpty())
            throw ApiExceptions.conflict("Another rental is committed to this unit");
        if(rental.getReservation()==null || rental.getReservation().getId()==null)
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Rental reservation binding missing");
        if(!currentQuery("select x from Reservation x where x.assignedUnit.id=:unit and x.id<>:reservation and x.status not in :terminal")
            .setParameter("unit",unit.getId()).setParameter("reservation",rental.getReservation().getId())
            .setParameter("terminal",List.of(ReservationStatus.CANCELLED,ReservationStatus.EXPIRED,ReservationStatus.REJECTED,ReservationStatus.COMPLETED)).getResultList().isEmpty())
            throw ApiExceptions.conflict("Another booking is committed to this unit");
        if(!currentQuery("select x from OverdueFollowUp x where x.rental.id=:rental and x.type='RECOVERY_HANDOFF'")
            .setParameter("rental",rental.getId()).getResultList().isEmpty())
            throw ApiExceptions.conflict("Recovery handoff blocks renewal; receiver reconciliation required");
        // Absence of a handoff row cannot prove absence of externally initiated recovery.
    }
    private TypedQuery<Object> currentQuery(String jpql) {
        var query=em.createQuery(jpql,Object.class).setMaxResults(1);
        if(TransactionSynchronizationManager.isActualTransactionActive()
            &&!TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        return query;
    }
}
