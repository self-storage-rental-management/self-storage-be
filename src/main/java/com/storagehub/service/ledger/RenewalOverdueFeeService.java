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
    public Quote quote(Renewal renewal,LedgerStore.Snapshot snapshot,Instant now,
        com.storagehub.api.renewal.RenewalQuoteResponse.Terms terms) {
        if (!Boolean.TRUE.equals(terms.continuousPeriod())) return quote(renewal,snapshot,now);
        var rental = renewal.getRental();
        var boundary = periods.require(rental).endExclusive().atStartOfDay(BusinessTime.ZONE).toInstant();
        var fee = quote(rental,snapshot,now.isBefore(boundary) ? now : boundary,true);
        return new Quote(fee.overdueDays(), fee.accrued(), fee.outstanding(), fee.unposted(),
            fee.dueAt(), Instant.MAX, fee.sourceRef(), fee.posted());
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
        // The fixed overdue timeline uses the business timezone independently of an optional renewal publication.
        ZoneId zone=BusinessTime.ZONE;LocalDate today=now.atZone(zone).toLocalDate();
        long days=Math.max(0,ChronoUnit.DAYS.between(period.lastPermittedDate(),today)-(signing?1:0));
        BigDecimal monthlyRent=rental.getMonthlyPrice();
        if(monthlyRent==null||monthlyRent.signum()<=0) {
            if(days>0)throw ApiExceptions.conflict("DEFERRED_SOURCE: Monthly rental price is missing or invalid");
            monthlyRent=BigDecimal.ZERO;
        }
        BigDecimal dailyRent=monthlyRent.divide(BigDecimal.valueOf(30),2,RoundingMode.HALF_UP);
        if(dailyRent.signum()<0||dailyRent.precision()>12)throw ApiExceptions.conflict("Daily overdue fee amount is unsupported");
        BigDecimal overdueDailyRate=dailyRent.multiply(new BigDecimal("1.5")).setScale(2,RoundingMode.HALF_UP);
        BigDecimal accrued=overdueDailyRate.multiply(BigDecimal.valueOf(days)).setScale(2,RoundingMode.HALF_UP);
        if(accrued.precision()>12)throw ApiExceptions.conflict("Accrued overdue fee amount is unsupported");
        String version=period.lastPermittedDate()+":"+overdueDailyRate.toPlainString()+":MONTHLY_RENT_DIV_30_X_1_5:"+zone.getId();
        return new Daily(days,accrued,today.atStartOfDay(zone).toInstant(),today.plusDays(1).atStartOfDay(zone).toInstant(),version,period.lastPermittedDate());
    }
}
