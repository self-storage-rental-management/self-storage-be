package com.storagehub.service.rental.period;

import com.storagehub.domain.model.Rental;
import jakarta.persistence.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One locked verification per legacy Rental; no date or historical evidence is rewritten. */
@Service @RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.rental-period.enabled",havingValue="true")
public class ConfirmedRentalPeriodImport {
    private final EntityManager em;
    private final ConfirmedRentalPeriods periods;
    private final ProjectHandoffRentalPeriodAdapter historical;
    @Transactional(readOnly=true)
    public List<UUID> candidates(UUID cursor) {
        var query=em.createQuery("select r.id from Rental r"+(cursor==null?"":" where r.id>:cursor")+" order by r.id",UUID.class);
        if(cursor!=null)query.setParameter("cursor",cursor);
        return query.setMaxResults(100).getResultList();
    }
    @Transactional
    public void confirm(UUID id) {
        var rental=em.find(Rental.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(rental==null||periods.contains(rental))return;
        historical.readHistorical(rental).flatMap(d->RentalPeriodResolver.normalize(rental,d)).ifPresent(p->periods.confirm(rental,p));
    }
}
