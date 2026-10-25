package com.storagehub.service.ledger;

import com.storagehub.time.BusinessTime;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.service.rental.period.RentalPeriodResolver;
import com.storagehub.service.renewal.integration.RenewalPolicyPublicationService;
import jakarta.persistence.EntityManager;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Server-calculated daily fee. No fallback amount is substituted for an unpublished setting. */
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class RenewalOverdueFeeService {
    private final EntityManager em;
    private final RentalPeriodResolver periods;
    private final RenewalPolicyPublicationService policies;
    public record Quote(long overdueDays,BigDecimal accrued,BigDecimal outstanding,BigDecimal unposted,
        Instant dueAt,Instant nextChangeAt,String sourceRef,List<LedgerStore.Obligation> posted) {}
    private record Daily(long days,BigDecimal accrued,Instant dayStart,Instant nextDay,String source,LocalDate lastPermittedDate) {}

    public BigDecimal estimate(Rental rental,Instant now) {return daily(rental,now).accrued();}
    public BigDecimal estimateAtSigning(Rental rental,Instant now) {return daily(rental,now,true).accrued();}
    public Quote quote(Renewal renewal,LedgerStore.Snapshot snapshot,Instant now) {
        // The signing day belongs to the new rent period; only preceding days are overdue fees.
        return quote(renewal.getRental(),snapshot,now,true);
    }
    public Quote quote(Rental rental,LedgerStore.Snapshot snapshot,Instant now) {
        return quote(rental,snapshot,now,false);
    }
    private Quote quote(Rental rental,LedgerStore.Snapshot snapshot,Instant now,boolean signing) {
        var daily=daily(rental,now,signing);
        if(!snapshot.rentalId().equals(rental.getId()))throw ApiExceptions.conflict("Overdue fee rental binding invalid");
        String prefix="rental-overdue:"+rental.getId()+":";
        var posted=snapshot.obligations().stream().filter(o->o.sourceRef().startsWith(prefix+daily.lastPermittedDate()+":")).toList();
        String source=prefix+daily.source();
        for(var o:posted)if(o.kind()!=LedgerStore.Kind.RENEWAL_REMAINDER||!o.sourceRef().startsWith(source+":"))
            throw ApiExceptions.conflict("Overdue fee policy changed; existing charges require reconciliation");
        BigDecimal charged=posted.stream().map(LedgerStore.Obligation::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(charged.compareTo(daily.accrued())>0||posted.stream().anyMatch(o->o.refunded().signum()!=0))
            throw ApiExceptions.conflict("Overdue fee charges require reconciliation");
        BigDecimal delta=daily.accrued().subtract(charged);
        BigDecimal outstanding=posted.stream().map(LedgerStore.Obligation::outstanding).reduce(delta,BigDecimal::add);
        return new Quote(daily.days(),daily.accrued(),outstanding,delta,daily.dayStart(),daily.nextDay(),source+":"+daily.days(),posted);
    }
    public boolean isFeeObligation(Renewal renewal,LedgerStore.Obligation obligation) {
        return obligation.kind()==LedgerStore.Kind.RENEWAL_REMAINDER
            &&obligation.sourceRef().startsWith("rental-overdue:"+renewal.getRental().getId()+":");
    }
    private Daily daily(Rental rental,Instant now) {
        return daily(rental,now,false);
    }
    private Daily daily(Rental rental,Instant now,boolean signing) {
        var period=periods.require(rental);
        var published=policies.read(rental.getFacility().getId(),now.atZone(BusinessTime.ZONE).toLocalDate())
            .filter(p->p.policy().term()!=null).orElseThrow(()->ApiExceptions.conflict("DEFERRED_SOURCE: Overdue fee timezone policy missing"));
        ZoneId zone=ZoneId.of(published.policy().term().timezone());LocalDate today=now.atZone(zone).toLocalDate();
        long days=Math.max(0,ChronoUnit.DAYS.between(period.lastPermittedDate(),today)-(signing?1:0));
        var settings=em.createQuery("select s from SystemSetting s where s.settingKey=:key",SystemSetting.class)
            .setParameter("key","lateFeeAmount").getResultList();
        BigDecimal rate=BigDecimal.ZERO;
        if(settings.size()==1)try {rate=new BigDecimal(settings.getFirst().getValue()).setScale(0,RoundingMode.UNNECESSARY);}
        catch(Exception invalid){throw ApiExceptions.conflict("DEFERRED_SOURCE: Daily overdue fee setting is invalid");}
        else if(days>0)throw ApiExceptions.conflict("DEFERRED_SOURCE: Daily overdue fee setting missing or ambiguous");
        if(rate.signum()<0||rate.precision()>12)throw ApiExceptions.conflict("Daily overdue fee amount is unsupported");
        BigDecimal accrued=rate.multiply(BigDecimal.valueOf(days));
        if(accrued.precision()>12)throw ApiExceptions.conflict("Accrued overdue fee amount is unsupported");
        String version=period.lastPermittedDate()+":"+rate.toPlainString()+":"+zone.getId();
        return new Daily(days,accrued,today.atStartOfDay(zone).toInstant(),today.plusDays(1).atStartOfDay(zone).toInstant(),version,period.lastPermittedDate());
    }
}
