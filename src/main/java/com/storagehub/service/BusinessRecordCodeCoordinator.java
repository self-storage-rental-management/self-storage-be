package com.storagehub.service;

import com.storagehub.domain.model.*;
import com.storagehub.service.rental.period.RentalPeriodResolver;
import com.storagehub.service.overdue.OverdueSources;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Persist overdue identity on first observation, independently of list pagination or a viewer. */
@Component @RequiredArgsConstructor
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="storagehub.record-codes.enabled",havingValue="true",matchIfMissing=true)
public class BusinessRecordCodeCoordinator {
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final BusinessRecordCodes codes;
    private final RentalPeriodResolver periods;
    private final ObjectProvider<OverdueSources.TermSource> terms;
    private final ObjectProvider<OverdueSources.FinancialSource> finances;
    private UUID cursor;
    @Scheduled(initialDelayString="${storagehub.record-codes.initial-delay-ms:1000}",fixedDelayString="${storagehub.record-codes.delay-ms:30000}")
    @Transactional
    public void register() {
        Instant at=Instant.now();
        for(String table:List.of("renewals","support_tickets")) {
            String type=table.equals("renewals")?"GH":"HT";
            jdbc.query("select lower(bin_to_uuid(t.id)) id,t.created_at from "+table+" t where not exists(select 1 from business_record_codes c where c.resource_key=concat(?,lower(bin_to_uuid(t.id)))) order by t.created_at,t.id limit 100",
                (rs,i)->Map.entry(rs.getString(1),rs.getTimestamp(2).toInstant()),type+":")
                .forEach(row->codes.assign(type,type+":"+row.getKey(),row.getValue()));
        }
        var query=em.createQuery("select r.id from Rental r where r.status=:active and r.actualReturnedAt is null"+
            (cursor==null?"":" and r.id>:cursor")+" order by r.id",UUID.class).setParameter("active",RentalStatus.active).setMaxResults(100);
        if(cursor!=null)query.setParameter("cursor",cursor);
        var ids=query.getResultList();
        for(UUID id:ids) {
            var rental=em.find(Rental.class,id);
            var period=periods.read(rental);
            var termSource=terms.getIfAvailable();
            var term=termSource==null?Optional.<OverdueSources.Term>empty():termSource.term(rental,at);
            if(period.isPresent()&&term.isPresent()&&period.get().lastPermittedDate().equals(term.get().lastPermittedDate())
                &&at.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().isAfter(period.get().lastPermittedDate()))
                codes.assign("QH","RENTAL_TERM:"+id,at);
            var financial=finances.getIfAvailable();
            if(financial!=null)financial.read(rental,at).ifPresent(f->{for(var obligation:f.obligations())
                if(obligation.outstanding().signum()>0&&at.isAfter(obligation.dueAt()))
                    codes.assign("QH","PAYMENT_DUE:"+id+":"+obligation.id(),at);});
        }
        cursor=ids.isEmpty()?null:ids.getLast();
    }
}
