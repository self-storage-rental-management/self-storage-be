package com.storagehub.service.overdue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.overdue.*;
import com.storagehub.common.api.*;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.renewal.RenewalReadService;
import com.storagehub.service.renewal.persistence.RenewalPersistence;
import jakarta.persistence.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.storagehub.service.overdue.OverdueSources.*;

@Service @RequiredArgsConstructor @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class OverdueService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Ho_Chi_Minh");
    private final EntityManager em;private final ObjectMapper mapper;private final RenewalPersistence store;private final AuditLogService audit;
    private final ObjectProvider<Clock> clocks;private final ObjectProvider<FinancialSource> finances;
    private final ObjectProvider<TermSource> terms;private final ObjectProvider<ReminderSource> reminders;private final ObjectProvider<RecoverySource> recoveries;
    private record Ref(String value,UUID rentalId,String kind,UUID obligationId) {}
    @Transactional(readOnly=true)
    public OverdueResponse.ListResult list(ActorPrincipal actor,OverdueQuery q,String correlation) {
        RenewalReadService.authorize(actor,true,false);if(q.facilityId()!=null)RenewalReadService.requireScope(actor,q.facilityId(),FacilityScopeLevel.READ);
        var scopes=actor.facilityScopes().entrySet().stream().filter(e->e.getValue()!=null&&e.getValue().includes(FacilityScopeLevel.READ)).map(Map.Entry::getKey).toList();
        var query=em.createQuery("select r from Rental r join fetch r.customer join fetch r.facility join fetch r.storageUnit where r.facility.id in :ids"+(q.facilityId()==null?"":" and r.facility.id=:facility"),Rental.class).setParameter("ids",scopes);
        if(q.facilityId()!=null)query.setParameter("facility",q.facilityId());
        Instant asOf=now();var missing=new TreeSet<String>();var rows=new ArrayList<OverdueResponse>();
        for(var rental:query.getResultList())rows.addAll(cases(rental,asOf,q.kind(),missing));
        String search=q.search().toLowerCase(Locale.ROOT);rows.removeIf(r->!search.isEmpty()&&!r.caseRef().toLowerCase(Locale.ROOT).contains(search)&&!r.rentalId().toString().contains(search)&&!r.customerName().toLowerCase(Locale.ROOT).contains(search)&&!r.storageUnitCode().toLowerCase(Locale.ROOT).contains(search));
        Comparator<OverdueResponse> order=switch(q.sort()){case "overdueDays"->Comparator.comparingLong(OverdueResponse::overdueDays);case "caseRef"->Comparator.comparing(OverdueResponse::caseRef);default->Comparator.comparingInt(r->priority(r.priority()));};
        if(q.descending())order=order.reversed();rows.sort(order.thenComparing(OverdueResponse::caseRef));
        int from=(int)Math.min((long)q.page()*q.size(),rows.size());int end=Math.min(from+q.size(),rows.size());
        return new OverdueResponse.ListResult(List.copyOf(rows.subList(from,end)),new PageResponse.Pagination(q.page(),q.size(),rows.size(),(int)((rows.size()+(long)q.size()-1)/q.size()),q.sort()+","+(q.descending()?"desc":"asc")),correlation,asOf,missing.isEmpty()?"COMPLETE":"PARTIAL",List.copyOf(missing));
    }
    @Transactional(readOnly=true)
    public OverdueResponse detail(ActorPrincipal actor,String id){var ref=parse(id);var rental=visible(actor,ref,false);return current(rental,ref);}
    public OverdueResponse.FollowUp followUp(ActorPrincipal actor,String id,OverdueCommands.FollowUp body,String key) {
        var ref=parse(id);var r=visible(actor,ref,true);var user=lockActor(actor);r=store.lockRental(r.getId());em.refresh(r);visible(actor,ref,true);
        Object payload=Map.of("caseRef",id,"command",body);var replay=store.replay(user,"overdue_follow_up",key,r.getId(),payload);if(replay.isPresent())return decode(replay.get().dataJson());
        current(r,ref);var state=stream(id,body.expectedVersion());UUID notification=null;String policyRef=null,version=null;
        if(body.type()==OverdueCommands.Type.REMINDER){var source=reminders.getIfAvailable();if(source==null||!source.atomic())throw deferred("Reminder policy/delivery unavailable");var policy=source.policy(r).orElseThrow(()->deferred("Reminder policy UNKNOWN"));
            if(policy.reference()==null||policy.reference().isBlank()||policy.version()==null||policy.version().isBlank()||policy.cooldown()==null||policy.cooldown().isNegative()||policy.cooldown().isZero())throw deferred("Reminder policy incomplete");
            var previous=em.createQuery("select f from OverdueFollowUp f where f.caseRef=:id and f.type='REMINDER' order by f.recordedAt desc",OverdueFollowUp.class).setParameter("id",id).setMaxResults(1).getResultList();
            if(!previous.isEmpty()&&now().isBefore(previous.getFirst().getRecordedAt().plus(policy.cooldown())))throw ApiExceptions.conflict("Reminder throttled by shared policy");
            notification=source.enqueue(r,id,body.content(),key,now());if(notification==null)throw deferred("No persisted notification reference");policyRef=policy.reference();version=policy.version();
        }
        var event=new OverdueFollowUp(id,r,body.type().name(),body.content(),actor.userId(),now(),notification,policyRef,version);return finish(user,r,state,event,"overdue_follow_up",key,payload);
    }
    public OverdueResponse.FollowUp recovery(ActorPrincipal actor,String id,OverdueCommands.Recovery body,String key) {
        var ref=parse(id);var r=visible(actor,ref,true);var user=lockActor(actor);r=store.lockRental(r.getId());em.refresh(r);visible(actor,ref,true);
        Object payload=Map.of("caseRef",id,"command",body);var replay=store.replay(user,"overdue_recovery",key,r.getId(),payload);if(replay.isPresent())return decode(replay.get().dataJson());
        var current=current(r,ref);if(!current.kind().equals("RENTAL_TERM")||!current.recoveryEligible())throw ApiExceptions.conflict("Only policy-eligible rental-term expiry may enter Recovery");
        var state=stream(id,body.expectedVersion());var source=recoveries.getIfAvailable();if(source==null||!source.atomic())throw deferred("Authorized Recovery receiving service unavailable");
        var handoff=source.receive(r,id,body.reason(),key,now());if(handoff==null||handoff.reference()==null||handoff.status()==null||!Set.of("RECEIVED","ALREADY_RECEIVED").contains(handoff.status()))throw deferred("Recovery receiver has not acknowledged");
        var event=new OverdueFollowUp(id,r,"RECOVERY_HANDOFF",body.reason(),actor.userId(),now(),handoff.reference(),current.policyRef(),current.policyVersion());
        // Receiving service owns Recovery state. No unit/access/Rental status mutation here.
        return finish(user,r,state,event,"overdue_recovery",key,payload);
    }
    @Transactional(readOnly=true)
    public PageResponse<OverdueResponse.FollowUp> history(ActorPrincipal actor,String id,int page,int size,String correlation) {
        var ref=parse(id);visible(actor,ref,false);if(page<0||size<1||size>100||(long)page*size>Integer.MAX_VALUE)throw ApiExceptions.validation("Invalid pagination",null);
        long revision=revision(id);var rows=em.createQuery("select f from OverdueFollowUp f where f.caseRef=:id order by f.recordedAt desc,f.id",OverdueFollowUp.class).setParameter("id",id).setFirstResult(page*size).setMaxResults(size).getResultList().stream().map(f->response(f,revision)).toList();
        long count=em.createQuery("select count(f) from OverdueFollowUp f where f.caseRef=:id",Long.class).setParameter("id",id).getSingleResult();return PageResponse.from(new PageImpl<>(rows,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"recordedAt").and(Sort.by("id"))),count),correlation);
    }
    private List<OverdueResponse> cases(Rental r,Instant at,String kind,Set<String> missing) {
        if(r.getCustomer()==null||r.getFacility()==null||r.getStorageUnit()==null||r.getCustomer().getFullName()==null||r.getStorageUnit().getCode()==null)throw ApiExceptions.conflict("Rental relationships inconsistent");
        var result=new ArrayList<OverdueResponse>();
        if(!kind.equals("PAYMENT_DUE")&&r.getActualReturnedAt()==null&&r.getStatus()!=RentalStatus.completed){
            var source=terms.getIfAvailable();var t=source==null?null:source.term(r,at).orElse(null);
            if(t==null)missing.add("RENTAL_TERM_POLICY_DATE_CUTOFF");else {
                if(t.policyRef()==null||t.policyRef().isBlank()||t.policyVersion()==null||t.policyVersion().isBlank()||t.lastPermittedDate()==null||!Objects.equals(t.lastPermittedDate(),r.getContractEndDate())||t.warningThroughDay()<1||t.seriousThroughDay()<=t.warningThroughDay()||t.urgentThroughDay()<=t.seriousThroughDay()||t.recoveryFromDay()!=t.urgentThroughDay()+1||t.recoveryCutoff()==null||t.recoveryStart()==null||!t.recoveryStart().isAfter(t.recoveryCutoff()))throw ApiExceptions.conflict("Authoritative overdue term policy/date mapping inconsistent");
                long days=ChronoUnit.DAYS.between(t.lastPermittedDate(),at.atZone(ZONE).toLocalDate());
                if(days>0){String priority=days<=t.warningThroughDay()?"WARNING":days<=t.seriousThroughDay()?"SERIOUS":days<=t.urgentThroughDay()?"URGENT":"RECOVERY";String ref="RENTAL_TERM:"+r.getId();boolean eligible=days>=t.recoveryFromDay()&&!at.isBefore(t.recoveryStart());result.add(row(r,ref,"RENTAL_TERM",days,priority,null,null,null,null,t.policyRef(),t.policyVersion(),t.recoveryCutoff(),eligible));}
            }
        }
        if(!kind.equals("RENTAL_TERM")) {
            var source=finances.getIfAvailable();var f=source==null?null:source.read(r,at).orElse(null);
            if(f==null)missing.add("AUTHORITATIVE_OBLIGATIONS_ALLOCATIONS");else {
                if(f.checkedAt()==null||f.checkedAt().isAfter(at)||f.obligations()==null)throw ApiExceptions.conflict("Authoritative financial state incomplete");var ids=new HashSet<UUID>();
                for(var o:f.obligations()){
                    if(o==null||o.id()==null||!r.getId().equals(o.rentalId())||!ids.add(o.id())||o.dueAt()==null||o.outstanding()==null||o.outstanding().signum()<0||!"VND".equals(o.currency()))throw ApiExceptions.conflict("Authoritative obligation inconsistent");
                    if(at.isAfter(o.dueAt())&&o.outstanding().signum()>0){String ref="PAYMENT_DUE:"+r.getId()+":"+o.id();long days=Math.max(0,ChronoUnit.DAYS.between(o.dueAt().atZone(ZONE).toLocalDate(),at.atZone(ZONE).toLocalDate()));result.add(row(r,ref,"PAYMENT_DUE",days,"PAYMENT_DUE",o.id(),o.dueAt(),o.outstanding(),o.currency(),null,null,null,false));}
                }
            }
        }
        return result;
    }
    private OverdueResponse row(Rental r,String ref,String kind,long days,String priority,UUID obligation,Instant due,java.math.BigDecimal amount,String currency,String policy,String version,Instant cutoff,boolean recovery){return new OverdueResponse(ref,r.getId(),r.getFacility().getId(),r.getCustomer().getId(),r.getCustomer().getFullName(),r.getStorageUnit().getCode(),kind,days,priority,obligation,due,amount,currency,policy,version,cutoff,recovery,revision(ref));}
    private OverdueResponse current(Rental r,Ref ref){var missing=new TreeSet<String>();var rows=cases(r,now(),ref.kind(),missing);if(!missing.isEmpty())throw deferred(String.join(",",missing));return rows.stream().filter(c->c.caseRef().equals(ref.value())).findFirst().orElseThrow(()->ApiExceptions.conflict("Case is no longer overdue"));}
    private Rental visible(ActorPrincipal actor,Ref ref,boolean write){RenewalReadService.authorize(actor,true,write);var r=em.find(Rental.class,ref.rentalId());if(r==null)throw ApiExceptions.notFound("Overdue case not found");var scope=actor.facilityScopes().get(r.getFacility().getId());if(scope==null||!scope.includes(write?FacilityScopeLevel.MANAGE:FacilityScopeLevel.READ))throw ApiExceptions.notFound("Overdue case not found");return r;}
    private OverdueFollowUpState stream(String ref,Long version){if(version==null||version<0)throw ApiExceptions.validation("expectedVersion required",null);var s=em.find(OverdueFollowUpState.class,ref,LockModeType.PESSIMISTIC_WRITE);if(s==null){if(version!=0)throw ApiExceptions.conflict("Follow-up revision stale");s=new OverdueFollowUpState(ref);em.persist(s);}else if(s.getRevision()!=version)throw ApiExceptions.conflict("Follow-up revision stale");return s;}
    private long revision(String ref){var state=em.find(OverdueFollowUpState.class,ref);return state==null?0:state.getRevision();}
    private OverdueResponse.FollowUp finish(User user,Rental r,OverdueFollowUpState state,OverdueFollowUp event,String op,String key,Object payload){state.appended();em.persist(event);em.flush();var response=response(event,state.getRevision());audit.recordMutation(user,op,"Rental",r.getId(),r.getFacility().getId(),null,response);store.remember(user,op,key,r.getId(),payload,201,response);return response;}
    private OverdueResponse.FollowUp response(OverdueFollowUp f,long revision){return new OverdueResponse.FollowUp(f.getId(),f.getCaseRef(),f.getType(),f.getContent(),f.getActorId(),f.getRecordedAt(),f.getExternalRef(),f.getPolicyRef(),f.getPolicyVersion(),revision);}
    private User lockActor(ActorPrincipal a){var u=em.find(User.class,a.userId(),LockModeType.PESSIMISTIC_WRITE);if(u==null)throw ApiExceptions.unauthorized("Actor not found");return u;}
    private OverdueResponse.FollowUp decode(String json){try{return mapper.readValue(json,OverdueResponse.FollowUp.class);}catch(Exception e){throw ApiExceptions.conflict("Stored follow-up result invalid");}}
    private Ref parse(String value){try{var parts=value.split(":",-1);if(parts.length<2)throw new IllegalArgumentException();String kind=parts[0];if(!(kind.equals("RENTAL_TERM")&&parts.length==2||kind.equals("PAYMENT_DUE")&&parts.length==3))throw new IllegalArgumentException();UUID rental=UUID.fromString(parts[1]),obligation=parts.length==3?UUID.fromString(parts[2]):null;if(!value.equals(kind+":"+rental+(obligation==null?"":":"+obligation)))throw new IllegalArgumentException();return new Ref(value,rental,kind,obligation);}catch(IllegalArgumentException e){throw ApiExceptions.validation("Invalid stable overdue reference",null);}}
    private int priority(String value){return switch(value){case "RECOVERY"->5;case "URGENT"->4;case "SERIOUS"->3;case "PAYMENT_DUE"->2;default->1;};}
    private Instant now(){var c=clocks.getIfAvailable();return (c==null?Clock.systemUTC():c).instant();}
    private static RuntimeException deferred(String message){return ApiExceptions.conflict("DEFERRED_SOURCE: "+message);}
}
