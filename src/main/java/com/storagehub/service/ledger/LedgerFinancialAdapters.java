package com.storagehub.service.ledger;

import com.storagehub.domain.model.Rental;
import com.storagehub.service.RentalReadSources;
import com.storagehub.service.overdue.OverdueSources;
import com.storagehub.service.renewal.RenewalSources;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Complete only with writer-backed coverage. Never infer all debt paid from an empty new ledger. */
@Component @RequiredArgsConstructor @Transactional(readOnly=true)
@ConditionalOnProperty(name="storagehub.integration.ledger.enabled",havingValue="true")
public class LedgerFinancialAdapters implements RentalReadSources.FinancialSource,OverdueSources.FinancialSource {
    private final LedgerStore store;
    private final ObjectProvider<LedgerCoverageSource> coverage;
    private final ObjectProvider<Clock> clocks;
    private record Verified(LedgerStore.Snapshot snapshot,LedgerCoverageSource.Coverage proof) {}
    private Optional<Verified> verified(Rental rental,Instant now) {
        var source=coverage.getIfAvailable();if(source==null)return Optional.empty();
        return store.read(rental.getId()).filter(snapshot->rental.getCustomer()!=null&&rental.getFacility()!=null
            &&Objects.equals(snapshot.customerId(),rental.getCustomer().getId())&&Objects.equals(snapshot.facilityId(),rental.getFacility().getId())).flatMap(snapshot->source.verify(rental,snapshot,now).filter(p->
            Objects.equals(p.rentalId(),rental.getId())&&p.ledgerRevision()==snapshot.revision()&&p.sourceRef()!=null&&!p.sourceRef().isBlank()
            &&p.verifiedAt()!=null&&!p.verifiedAt().isAfter(now)&&p.billingMode()!=null).map(p->new Verified(snapshot,p)));
    }
    public Optional<RentalReadSources.Financial> read(Rental rental) {
        Instant now=now();return verified(rental,now).map(v->{
            var debts=v.snapshot().obligations();
            BigDecimal total=debts.stream().map(LedgerStore.Obligation::outstanding).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal overdue=debts.stream().filter(o->o.dueAt().isBefore(now)).map(LedgerStore.Obligation::outstanding).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal deposit=v.proof().securityDepositCovered()?v.snapshot().obligations().stream().filter(o->o.kind()==LedgerStore.Kind.SECURITY_DEPOSIT).map(o->o.allocated().subtract(o.refunded())).reduce(BigDecimal.ZERO,BigDecimal::add):null;
            LocalDate next=v.proof().billingMode()==RentalReadSources.BillingMode.PREPAID_FULL_PERIOD?null:debts.stream().filter(o->o.outstanding().signum()>0&&!o.dueAt().isBefore(now)).map(o->o.dueAt().atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate()).min(LocalDate::compareTo).orElse(null);
            return new RentalReadSources.Financial(rental.getId(),now,"VND",total,overdue,deposit,next,v.proof().billingMode());
        });
    }
    /** Different signature via a dedicated delegating bean below avoids conflicting read(Rental) return types. */
    public Optional<RenewalSources.Financial> approval(Rental rental) {
        Instant now=now();return verified(rental,now).filter(v->v.proof().disputesCovered()).map(v->new RenewalSources.Financial(now,v.snapshot().obligations().stream().filter(o->o.outstanding().signum()>0&&!o.dueAt().isAfter(now)).map(LedgerStore.Obligation::id).toList(),v.proof().unresolvedDispute()));
    }
    public boolean consistentThroughApproval(){var source=coverage.getIfAvailable();return source!=null&&source.consistentThroughApproval();}
    public Optional<OverdueSources.Finance> read(Rental rental,Instant now) {
        return verified(rental,now).map(v->new OverdueSources.Finance(now,v.snapshot().obligations().stream().map(o->new OverdueSources.Obligation(o.id(),rental.getId(),o.dueAt(),o.outstanding(),"VND")).toList()));
    }
    private Instant now(){var clock=clocks.getIfAvailable();return (clock==null?Clock.systemUTC():clock).instant();}
}
