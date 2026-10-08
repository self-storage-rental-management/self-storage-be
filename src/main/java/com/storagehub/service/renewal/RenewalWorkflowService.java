package com.storagehub.service.renewal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.*;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.domain.repo.RentalRepository;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.renewal.persistence.*;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class RenewalWorkflowService {
    private final RentalRepository rentals;private final RenewalReadService reads;private final RenewalPersistence store;
    private final EntityManager em;private final ObjectMapper mapper;private final AuditLogService audit;
    private final ObjectProvider<RenewalSources.PolicySource> policies;
    private final ObjectProvider<RenewalSources.PricingSource> pricing;
    private final ObjectProvider<RenewalSources.EligibilitySource> eligibility;
    private final ObjectProvider<RenewalSources.FinancialSource> finance;
    private final ObjectProvider<RenewalSources.ExtensionHoldSource> holds;
    private final ObjectProvider<RenewalSources.ApprovalLifecycleSource> lifecycles;
    private final ObjectProvider<Clock> clocks;
    private Instant now(){var c=clocks.getIfAvailable();return (c==null?Clock.systemUTC():c).instant();}
    public List<RenewalCommands.Option> options(ActorPrincipal a,UUID rentalId){
        var r=owned(a,rentalId);var g=gate(r,now());return prices(r,g).stream().map(p->new RenewalCommands.Option(p.packageCode(),p.months())).toList();
    }
    public RenewalQuoteResponse quote(ActorPrincipal a,UUID rentalId,RenewalCommands.Quote body){
        owned(a,rentalId);var r=store.lockRental(rentalId);em.refresh(r);checkOwner(a,r);Instant now=now();var g=gate(r,now);
        var terms=terms(r,g,body.pricingPackageCode());var expires=min(now.plus(g.policy.quoteTtl()),g.eligibility.recoveryCutoff());
        var q=store.storeQuote(r,r.getCustomer(),terms,now,expires);em.flush();return new RenewalQuoteResponse(q.getId(),r.getId(),a.userId(),terms,now,expires);
    }
    public RenewalResponse submit(ActorPrincipal a,UUID rentalId,RenewalCommands.Submit body,String key){
        owned(a,rentalId);var user=lockActor(a);var r=store.lockRental(rentalId);em.refresh(r);checkOwner(a,r);
        var replay=store.replay(user,"submit",key,rentalId,body);if(replay.isPresent())return decode(replay.get().dataJson(),RenewalResponse.class);
        Instant now=now();gate(r,now);var q=store.validQuote(body.renewalQuoteId(),r,user,now);var t=decode(q.getTermsJson(),RenewalQuoteResponse.Terms.class);
        requireSnapshot(r,t);
        // Conservative guard also covers legacy requests and phases not yet coordinated with D3.
        var existing=em.createQuery("select count(n) from Renewal n where n.rental.id=:id and n.status not in :terminal",Long.class)
            .setParameter("id",rentalId).setParameter("terminal",List.of(RenewalStatus.rejected,RenewalStatus.cancelled,RenewalStatus.completed)).getSingleResult();
        if(existing>0)throw ApiExceptions.conflict("Rental already has an open or unresolved legacy renewal");
        var n=new Renewal();n.setRental(r);n.setRequestedBy(user);n.setNewEndDate(t.newEndDate());n.setAmount(t.totalAfterDiscount());em.persist(n);
        store.acceptInitial(r,n,q,now,body.note());return finish(user,"submit",key,rentalId,body,n,201,null);
    }
    public RenewalResponse edit(ActorPrincipal a,UUID id,RenewalCommands.Edit body,String key){
        var n=reads.find(a,id,false);var user=lockActor(a);var r=store.lockRental(n.getRental().getId());em.refresh(r);checkOwner(a,r);
        var replay=store.replay(user,"edit",key,id,body);if(replay.isPresent())return decode(replay.get().dataJson(),RenewalResponse.class);
        em.refresh(n);pending(n);var wf=store.lockWorkflow(id,body.expectedVersion());Instant now=now();gate(r,now);
        var q=store.validQuote(body.renewalQuoteId(),r,user,now);var t=decode(q.getTermsJson(),RenewalQuoteResponse.Terms.class);requireSnapshot(r,t);
        var before=Map.of("status",n.getStatus(),"revision",wf.getAcceptedRevision().getRevisionNumber());
        store.acceptRevision(wf,q,now,body.note());n.setNewEndDate(t.newEndDate());n.setAmount(t.totalAfterDiscount());return finish(user,"edit",key,id,body,n,200,before);
    }
    public RenewalResponse cancel(ActorPrincipal a,UUID id,RenewalCommands.Cancel body,String key){
        var n=reads.find(a,id,false);var user=lockActor(a);var r=store.lockRental(n.getRental().getId());em.refresh(r);checkOwner(a,r);
        var replay=store.replay(user,"cancel",key,id,body);if(replay.isPresent())return decode(replay.get().dataJson(),RenewalResponse.class);
        em.refresh(n);pending(n);var wf=store.lockWorkflow(id,body.expectedVersion());var before=Map.of("status",n.getStatus());
        n.setStatus(RenewalStatus.cancelled);wf.cancelled(body.reason());store.releaseSlot(wf);
        return finish(user,"cancel",key,id,body,n,200,before);
    }
    public RenewalResponse decision(ActorPrincipal a,UUID id,RenewalCommands.Decision body,String key){
        RenewalReadService.authorize(a,true,true);var n=reads.findForDecision(a,id);RenewalReadService.requireScope(a,n.getRental().getFacility().getId(),FacilityScopeLevel.MANAGE);
        var user=lockActor(a);var r=store.lockRental(n.getRental().getId());em.refresh(r);
        RenewalReadService.requireScope(a,r.getFacility().getId(),FacilityScopeLevel.MANAGE);
        var replay=store.replay(user,"decision",key,id,body);if(replay.isPresent())return decode(replay.get().dataJson(),RenewalResponse.class);
        em.refresh(n);pending(n);var wf=store.lockWorkflow(id,body.expectedVersion());Instant now=now();var before=Map.of("status",n.getStatus());
        if(body.decision()==RenewalCommands.DecisionType.REJECT){
            if(body.reason()==null||body.reason().isBlank())throw ApiExceptions.validation("REJECT requires reason",null);
            n.setStatus(RenewalStatus.rejected);wf.reviewed(user,now,body.reason(),null,null);store.releaseSlot(wf);
        }else{
            var g=gate(r,now);var accepted=decode(wf.getAcceptedRevision().getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class);requireSnapshot(r,accepted);
            if(!store.canonical(accepted).equals(store.canonical(terms(r,g,accepted.pricingPackageCode()))))throw ApiExceptions.conflict("Terms changed: new quote and Customer confirmation required");
            var f=finance.getIfAvailable();if(f==null||!f.consistentThroughApproval())throw deferred("Consistent financial source missing");var state=f.read(r).orElseThrow(()->deferred("Financial state UNKNOWN"));
            if(state.checkedAt()==null||state.checkedAt().isAfter(now)||state.dueObligations()==null)throw deferred("Financial state incomplete");
            if(state.unresolvedDispute()||!state.dueObligations().isEmpty())throw ApiExceptions.conflict("Due obligations or dispute block approval");
            var h=holds.getIfAvailable();if(h==null||!h.participatesInTransaction())throw deferred("Atomic shared extension hold unavailable");
            var lifecycle=lifecycles.getIfAvailable();if(lifecycle==null||!lifecycle.ready(r))throw deferred("Approval expiry/payment/hold lifecycle unavailable");
            Instant deadline=min(now.plus(g.policy.paymentWindow()),g.eligibility.recoveryCutoff());
            UUID hold=h.acquire(r,id,accepted.extensionStartDate(),accepted.extensionEndExclusive(),deadline);
            if(hold==null)throw deferred("Shared hold returned no authoritative reference");
            n.setStatus(RenewalStatus.approved);wf.reviewed(user,now,body.reason(),deadline,hold);
        }
        return finish(user,"decision",key,id,body,n,200,before);
    }
    private RenewalResponse finish(User user,String operation,String key,UUID resource,Object payload,Renewal n,int status,Object before){
        em.flush();var result=project(n);audit.recordMutation(user,"renewal_"+operation,"Renewal",n.getId(),n.getRental().getFacility().getId(),before,Map.of("result",result,"command",payload));
        store.remember(user,operation,key,resource,payload,status,result);return result;
    }
    @Transactional(readOnly=true)
    public RenewalResponse project(Renewal n){
        return project(n,null,false);
    }
    @Transactional(readOnly=true)
    public RenewalResponse project(Renewal n,ActorPrincipal actor,boolean manager){
        var legacy=reads.baseProjection(n);
        var wf=em.find(RenewalWorkflow.class,n.getId());if(wf==null)return legacy;
        var rev=wf.getAcceptedRevision();var t=decode(rev.getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class);
        if (!Objects.equals(rev.getRenewal().getId(), n.getId())
            || !Objects.equals(rev.getQuote().getRental().getId(), n.getRental().getId())
            || !Objects.equals(rev.getQuote().getCustomer().getId(), n.getRequestedBy().getId())
            || t.totalAfterDiscount()==null || n.getAmount().compareTo(t.totalAfterDiscount())!=0
            || !Objects.equals(n.getNewEndDate(),t.newEndDate()))
            throw ApiExceptions.conflict("Accepted Renewal terms do not match persisted request");
        String review="UNKNOWN";
        if(n.getStatus()==RenewalStatus.pending){try{var current=terms(n.getRental(),gate(n.getRental(),now()),t.pricingPackageCode());review=store.canonical(t).equals(store.canonical(current))?"READY":"AWAITING_CUSTOMER_CONFIRMATION";}catch(com.storagehub.common.api.ApiException ignored){}}
        var financial=legacy.financialCheck();var fs=finance.getIfAvailable();
        if(fs!=null){var state=fs.read(n.getRental());if(state.isPresent()&&state.get().checkedAt()!=null&&!state.get().checkedAt().isAfter(now())&&state.get().dueObligations()!=null){var s=state.get();financial=new RenewalResponse.FinancialCheck("COMPLETE",s.checkedAt(),s.dueObligations(),s.unresolvedDispute());}}
        var actions=new ArrayList<String>();
        if(actor!=null&&n.getStatus()==RenewalStatus.pending){
            if(!manager&&actor.hasRole(RoleCode.CUSTOMER)&&actor.userId().equals(n.getRental().getCustomer().getId())){actions.add("CANCEL");if(!review.equals("UNKNOWN"))actions.add("ACCEPT_REVISED_QUOTE");}
            var scope=actor.facilityScopes().get(n.getRental().getFacility().getId());
            if(manager&&actor.hasRole(RoleCode.MANAGER)&&actor.hasPermission(SystemPermission.MANAGE_RENTALS)&&scope!=null&&scope.includes(FacilityScopeLevel.MANAGE)){
                actions.add("REJECT");var h=holds.getIfAvailable();
                var f=finance.getIfAvailable();var lifecycle=lifecycles.getIfAvailable();
                if(review.equals("READY")&&financial.completeness().equals("COMPLETE")&&Boolean.FALSE.equals(financial.hasUnresolvedDispute())&&financial.blockingObligationRefs().isEmpty()&&h!=null&&h.participatesInTransaction()&&f!=null&&f.consistentThroughApproval()&&lifecycle!=null&&lifecycle.ready(n.getRental()))actions.add("APPROVE");
            }
        }
        var reasons=new ArrayList<String>();
        if(n.getStatus()==RenewalStatus.pending){
            if(review.equals("UNKNOWN"))reasons.add("Shared policy/pricing/eligibility is unavailable or operational eligibility blocks review");
            if(review.equals("AWAITING_CUSTOMER_CONFIRMATION"))reasons.add("Terms changed: Customer must accept a new quote");
            if(!financial.completeness().equals("COMPLETE"))reasons.add("Financial obligations/dispute source is UNKNOWN");
            else if(Boolean.TRUE.equals(financial.hasUnresolvedDispute())||!financial.blockingObligationRefs().isEmpty())reasons.add("Due obligations or unresolved dispute block approval");
            var f=finance.getIfAvailable();if(f==null||!f.consistentThroughApproval())reasons.add("Financial consistency through approval is unavailable");
            var h=holds.getIfAvailable();if(h==null||!h.participatesInTransaction())reasons.add("Atomic extension hold is unavailable");
            var lifecycle=lifecycles.getIfAvailable();if(lifecycle==null||!lifecycle.ready(n.getRental()))reasons.add("Approval expiry/payment/hold lifecycle is unavailable");
        }
        return new RenewalResponse(legacy.id(),legacy.rentalId(),legacy.customer(),legacy.facility(),legacy.storageUnit(),legacy.status(),review,t.oldEndDate(),legacy.newEndDate(),legacy.amount(),t.currency(),legacy.createdAt(),wf.getVersion(),legacy.requestedBy(),rev.getQuote().getId(),rev.getRevisionNumber(),wf.getReviewer()==null?null:wf.getReviewer().getId(),wf.getReviewedAt(),wf.getReviewReason(),wf.getPaymentDeadline(),wf.getExtensionHoldRef(),financial,actions,reasons,t,wf.getCancellationReason());
    }
    private Rental owned(ActorPrincipal a,UUID id){RenewalReadService.authorize(a,false,false);return rentals.findByIdAndCustomer_Id(id,a.userId()).orElseThrow(()->ApiExceptions.notFound("Rental was not found"));}
    private static void checkOwner(ActorPrincipal a,Rental r){if(r.getCustomer()==null||!a.userId().equals(r.getCustomer().getId()))throw ApiExceptions.notFound("Rental was not found");}
    private User lockActor(ActorPrincipal a){var user=em.find(User.class,a.userId(),LockModeType.PESSIMISTIC_WRITE);if(user==null)throw ApiExceptions.unauthorized("Actor no longer exists");return user;}
    private record Gate(RenewalSources.Policy policy,RenewalSources.Eligibility eligibility){}
    private Gate gate(Rental r,Instant now){
        if(r.getStatus()!=RentalStatus.active||r.getContractEndDate()==null)throw ApiExceptions.conflict("Move-out/non-active or incomplete rental blocks renewal");
        var source=policies.getIfAvailable();if(source==null)throw deferred("BO renewal policy missing");var p=source.read(r,r.getContractEndDate().plusDays(1)).orElseThrow(()->deferred("BO renewal policy missing"));
        if(p.reference()==null||p.reference().isBlank()||p.version()==null||p.version().isBlank()||p.eligiblePackageIds()==null||p.quoteTtl()==null||p.quoteTtl().isNegative()||p.quoteTtl().isZero()||p.paymentWindow()==null||p.paymentWindow().isNegative()||p.paymentWindow().isZero()||p.depositRate()==null||p.requestWindowDays()<0)throw deferred("Incomplete BO policy");
        var es=eligibility.getIfAvailable();if(es==null)throw deferred("Eligibility/cutoff missing");var e=es.read(r,now).orElseThrow(()->deferred("Eligibility UNKNOWN"));
        if(!e.datesVerified()||e.recoveryCutoff()==null)throw deferred("Unverified date provenance/cutoff");
        if(e.recoveryBlocked()||!e.feasible()||!now.isBefore(e.recoveryCutoff()))throw ApiExceptions.conflict("Recovery/cutoff conflict");
        if(now.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().isBefore(r.getContractEndDate().minusDays(p.requestWindowDays())))throw ApiExceptions.conflict("Renewal window not open");return new Gate(p,e);
    }
    private List<RenewalSources.Price> prices(Rental r,Gate g){
        var s=pricing.getIfAvailable();if(s==null)throw deferred("Authoritative renewal pricing/rounding source missing");
        var data=s.read(r,r.getContractEndDate().plusDays(1)).orElseThrow(()->deferred("Renewal pricing UNKNOWN"));
        if(r.getStorageUnit()==null||r.getStorageUnit().getUnitType()==null)throw ApiExceptions.conflict("Rental unit type missing");
        var codes=new HashSet<String>();var result=new ArrayList<RenewalSources.Price>();
        for(var p:data){if(p==null||p.packageId()==null)throw deferred("Incomplete pricing");if(!g.policy.eligiblePackageIds().contains(p.packageId()))continue;
            if(p.packageCode()==null||p.packageCode().isBlank()||p.packageCode().length()>30||p.packageVersion()==null||p.packageVersion().isBlank()||!Objects.equals(p.unitTypeId(),r.getStorageUnit().getUnitType().getId())||!"VND".equals(p.currency())||p.moneyScale()!=2||!codes.add(p.packageCode()))throw deferred("Pricing mapping/rounding incomplete or ambiguous");
            RenewalTermCalculator.calculate(r.getContractEndDate(),p.months(),p.monthlyPrice(),p.discountRate(),g.policy.depositRate());result.add(p);}
        result.sort(Comparator.comparingInt(RenewalSources.Price::months).thenComparing(RenewalSources.Price::packageCode));return result;
    }
    private RenewalQuoteResponse.Terms terms(Rental r,Gate g,String code){
        var p=prices(r,g).stream().filter(x->x.packageCode().equals(code)).findFirst().orElseThrow(()->ApiExceptions.conflict("Package not eligible for Renewal"));
        var t=RenewalTermCalculator.calculate(r.getContractEndDate(),p.months(),p.monthlyPrice(),p.discountRate(),g.policy.depositRate());
        if(t.netRent().precision()>14)throw ApiExceptions.conflict("Shared renewal amount exceeds supported precision");
        return new RenewalQuoteResponse.Terms(r.getContractEndDate(),t.startDate(),t.endExclusive(),t.newEndDate(),p.unitTypeId(),p.packageCode(),p.packageId(),p.packageVersion(),p.months(),p.monthlyPrice(),p.discountRate(),t.subtotal(),t.discountAmount(),t.netRent(),t.depositAmount(),t.remainder(),p.currency(),g.policy.reference(),g.policy.version(),r.getStorageUnit().getId(),r.getFacility().getId());
    }
    private static void requireSnapshot(Rental r,RenewalQuoteResponse.Terms t){if(!Objects.equals(r.getContractEndDate(),t.oldEndDate())||r.getStorageUnit()==null||r.getStorageUnit().getUnitType()==null||!Objects.equals(r.getStorageUnit().getId(),t.storageUnitId())||!Objects.equals(r.getFacility().getId(),t.facilityId())||!Objects.equals(r.getStorageUnit().getUnitType().getId(),t.unitTypeId()))throw ApiExceptions.conflict("Rental allocation/end changed; requote required");}
    private static void pending(Renewal n){if(n.getStatus()!=RenewalStatus.pending)throw ApiExceptions.conflict("Only pending Renewal may use D2 command");}
    private <T>T decode(String json,Class<T> type){try{return mapper.readValue(json,type);}catch(Exception e){throw ApiExceptions.conflict("Stored Renewal snapshot is invalid");}}
    private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
    private static RuntimeException deferred(String reason){return ApiExceptions.conflict("DEFERRED_SOURCE: "+reason);}
}
