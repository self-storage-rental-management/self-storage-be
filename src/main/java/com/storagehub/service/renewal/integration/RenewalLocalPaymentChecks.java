package com.storagehub.service.renewal.integration;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.service.ledger.LedgerStore;
import com.storagehub.service.overdue.OverdueSources;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Narrow DB checks for payment opening; no certification of complete historical finances or Recovery. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.renewal-simple-payment.enabled",havingValue="true")
public class RenewalLocalPaymentChecks {
    private final EntityManager em;
    private final ObjectProvider<LedgerStore> ledgers;
    private final ObjectProvider<OverdueSources.TermSource> terms;
    public Instant cutoff(Rental rental,Instant now) {
        requireRental(rental);
        var source=terms.getIfAvailable();var term=source==null?null:source.term(rental,now).orElse(null);
        if(term==null||term.recoveryCutoff()==null)throw ApiExceptions.conflict("DEFERRED_SOURCE: Published Renewal cutoff missing");
        if(!now.isBefore(term.recoveryCutoff()))throw ApiExceptions.conflict("Renewal cutoff has passed");
        return term.recoveryCutoff();
    }
    public void requireRental(Rental rental) {
        if(rental==null||rental.getStatus()!=RentalStatus.active||rental.getActualReturnedAt()!=null||rental.getCompletedAt()!=null)
            throw ApiExceptions.conflict("Only an active, unreturned Rental can renew");
        if(rental.getStorageUnit()==null||rental.getStorageUnit().getStatus()!=StorageUnitStatus.occupied||rental.getReservation()==null)
            throw ApiExceptions.conflict("Rental unit is no longer occupied or booking binding missing");
        if(em.createQuery("select count(t) from ReturnCase t where t.rental.id=:id",Long.class).setParameter("id",rental.getId()).getSingleResult()>0)
            throw ApiExceptions.conflict("Return case blocks Renewal payment");
        if(em.createQuery("select count(t) from OverdueFollowUp t where t.rental.id=:id and t.type='RECOVERY_HANDOFF'",Long.class).setParameter("id",rental.getId()).getSingleResult()>0)
            throw ApiExceptions.conflict("Recorded Recovery handoff blocks Renewal payment");
    }
    public void requireKnownFinance(Rental rental,UUID renewalId,Instant now) {
        var ledger=ledgers.getIfAvailable();
        if(ledger==null)throw ApiExceptions.conflict("DEFERRED_SOURCE: Renewal accounting disabled");
        var account=ledger.read(rental.getId());
        if(account.isPresent()) {
            var snapshot=account.get();
            if(!Objects.equals(snapshot.customerId(),rental.getCustomer().getId())||!Objects.equals(snapshot.facilityId(),rental.getFacility().getId()))
                throw ApiExceptions.conflict("Ledger resource binding changed");
            if(snapshot.obligations().stream().anyMatch(o->!Objects.equals(o.renewalId(),renewalId)&&o.outstanding().signum()>0&&!o.dueAt().isAfter(now)))
                throw ApiExceptions.conflict("Recorded due obligations block Renewal payment");
        }
        if(em.createQuery("select count(p) from PaymentComplaint p where p.reservation.id=:id and p.status in :statuses",Long.class)
            .setParameter("id",rental.getReservation().getId()).setParameter("statuses",List.of(PaymentComplaintStatus.PENDING,PaymentComplaintStatus.REVIEW_OVERDUE)).getSingleResult()>0)
            throw ApiExceptions.conflict("Unresolved payment complaint blocks Renewal payment");
    }
}
