package com.storagehub.service.renewal.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.*;
import com.storagehub.api.renewal.RenewalOperationCommands.*;
import com.storagehub.common.api.*;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.AuditLogService;
import com.storagehub.service.renewal.RenewalReadService;
import com.storagehub.service.renewal.persistence.*;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.storagehub.service.renewal.operations.RenewalOperationSources.*;

@Service @RequiredArgsConstructor @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class RenewalOperationService {
    public enum Audience { CUSTOMER, MANAGER, STAFF }
    public record Result(RenewalOperationResponse state,RenewalOperationResponse.Event event) {}
    private record Context(Renewal renewal,User user,RenewalWorkflow workflow) {}
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final RenewalPersistence store;
    private final AuditLogService audit;
    private final ObjectProvider<Clock> clocks;
    private final ObjectProvider<AuthorizationSource> authorizations;
    private final ObjectProvider<PolicySource> policies;
    private final ObjectProvider<CalendarSource> calendars;
    private final ObjectProvider<SafetySource> safetySources;
    private final ObjectProvider<AccountingSource> accountingSources;
    private final ObjectProvider<EvidenceSource> evidences;
    private final ObjectProvider<RefundSource> refunds;

    @Transactional(readOnly=true)
    public RenewalOperationResponse detail(ActorPrincipal actor,UUID id,Audience audience) {
        return projection(visible(actor,id,audience,false,Capability.READ));
    }
    @Transactional(readOnly=true)
    public RenewalExceptionProposalResponse exceptionProposal(ActorPrincipal actor,UUID id) {
        var n=visible(actor,id,Audience.CUSTOMER,false,Capability.READ);
        var w=em.find(RenewalWorkflow.class,id);var s=state(n);Instant checkedAt=now();
        if(s==null||s.getPendingExceptionRef()==null)
            return proposal(n,w,s,null,"NONE",checkedAt,null,null,null,null,false,List.of("NO_CURRENT_PROPOSAL"));
        var e=linked(n,s.getPendingExceptionRef(),"EXCEPTION");
        var d=decode(e.getPayloadJson(),ExceptionDecision.class);
        if(d.action()!=ExceptionAction.APPROVE_RESCHEDULE_BEFORE_CUTOFF)
            return proposal(n,w,s,e.getId(),"UNAVAILABLE",checkedAt,null,null,null,null,false,List.of("NOT_RESCHEDULE_PROPOSAL"));
        nonterminal(n);requireProposalState(s,e);
        if(d.appointmentAt()==null||d.revisedDeadline()==null)throw ApiExceptions.conflict("Stored exception proposal is incomplete");
        if(s.getRecoveryCutoff()==null)
            return proposal(n,w,s,e.getId(),"UNAVAILABLE",checkedAt,d.appointmentAt(),null,d.revisedDeadline(),null,false,List.of("SOURCE_UNAVAILABLE"));
        if(!checkedAt.isBefore(d.appointmentAt())||!checkedAt.isBefore(d.revisedDeadline())||!checkedAt.isBefore(s.getRecoveryCutoff()))
            return proposal(n,w,s,e.getId(),"UNAVAILABLE",checkedAt,d.appointmentAt(),null,d.revisedDeadline(),min(s.getRecoveryCutoff(),min(d.appointmentAt(),d.revisedDeadline())),false,List.of("PROPOSAL_EXPIRED"));
        // No fake readiness. The proposal remains visible, but unusable, until owner sources exist.
        var p=policies.getIfAvailable();var calendar=calendars.getIfAvailable();var safety=safetySources.getIfAvailable();
        if(w==null||p==null||calendar==null||!calendar.atomic()||safety==null||!safety.atomic())
            return proposal(n,w,s,e.getId(),"UNAVAILABLE",checkedAt,d.appointmentAt(),null,d.revisedDeadline(),null,false,List.of("SOURCE_UNAVAILABLE"));
        var valid=validateReschedule(new Context(n,null,w),s,e,d);
        checkedAt=now();Instant validUntil=min(valid.cutoff(),valid.slot().start());boolean allowed=checkedAt.isBefore(validUntil);
        return proposal(n,w,s,e.getId(),allowed?"AVAILABLE":"UNAVAILABLE",checkedAt,valid.slot().start(),valid.slot().end(),d.revisedDeadline(),validUntil,allowed,allowed?List.of():List.of("PROPOSAL_EXPIRED"));
    }
    private RenewalExceptionProposalResponse proposal(Renewal n,RenewalWorkflow w,RenewalOperationState s,UUID ref,String status,Instant checked,
        Instant start,Instant end,Instant deadline,Instant validUntil,boolean allowed,List<String> reasons) {
        return new RenewalExceptionProposalResponse(n.getId(),w==null?null:w.getVersion(),ref,status,checked,
            s==null?null:s.getAppointmentStart(),s==null?null:s.getAppointmentEnd(),s==null?null:s.getEffectiveSigningDeadline(),
            start,end,deadline,validUntil,allowed,List.copyOf(reasons));
    }
    public Result deposit(ActorPrincipal actor,UUID id,RenewalOperationCommands.Version body,String key) {
        var c=context(actor,id,Audience.CUSTOMER,Capability.READ,key,"d3_deposit",body,body.expectedVersion());
        var old=replay(c,"d3_deposit",key,body);if(old.isPresent())return old.get();
        var n=c.renewal();if(n.getStatus()!=RenewalStatus.approved||state(n)!=null)throw ApiExceptions.conflict("Renewal is not awaiting deposit");
        var terms=terms(c);Instant now=now();var policy=policy(n);var safety=safety();
        Instant cutoff=safety.check(n,terms,c.workflow().getExtensionHoldRef(),now);
        Instant paymentDeadline=c.workflow().getPaymentDeadline();
        if(cutoff==null||paymentDeadline==null)throw deferred("Payment deadline/recovery cutoff missing");
        beforeOrEqual(now,min(paymentDeadline,cutoff));
        Instant prospective=min(now.plus(policy.signingWindow()),cutoff);
        var calendar=calendar();if(!calendar.feasible(n,now,prospective))throw ApiExceptions.conflict("No feasible signing slot before deadline");
        var paid=accounting().deposit(n,terms,actor.userId(),key,paymentDeadline);
        if(paid==null||paid.paymentRef()==null||!id.equals(paid.renewalId())||paid.outcome()==null)throw deferred("Invalid shared deposit result");
        if(paid.outcome()==Outcome.SUCCESS) {
            Instant verifiedNow=now();
            if(paid.amount()==null||paid.amount().compareTo(terms.renewalDepositAmount())!=0||!terms.currency().equals(paid.currency())||paid.paidAt()==null||paid.paidAt().isAfter(verifiedNow)||paid.paidAt().isAfter(paymentDeadline)||paid.paidAt().isAfter(cutoff))throw ApiExceptions.conflict("Verified deposit does not match accepted terms/deadline");
            cutoff=safety.check(n,terms,c.workflow().getExtensionHoldRef(),verifiedNow);if(cutoff==null)throw deferred("Recovery cutoff disappeared");
            Instant deadline=min(paid.paidAt().plus(policy.signingWindow()),cutoff);beforeOrEqual(verifiedNow,deadline);
            if(!calendar.feasible(n,verifiedNow,deadline))throw ApiExceptions.conflict("No feasible signing slot for verified payment");
            safety.retain(n,c.workflow().getExtensionHoldRef(),deadline);
            var s=new RenewalOperationState(n);s.deposit(paid.paymentRef(),paid.paidAt(),deadline,cutoff,policy.reference(),policy.version());em.persist(s);
            n.setStatus(RenewalStatus.deposit_paid);
        }
        return finish(c,"d3_deposit",key,body,event(c,"DEPOSIT",paid),200);
    }
    public Result appointment(ActorPrincipal actor,UUID id,Appointment body,String key,boolean edit) {
        String op=edit?"d3_reschedule":"d3_appointment";
        var c=context(actor,id,Audience.CUSTOMER,Capability.READ,key,op,body,body.expectedVersion());var old=replay(c,op,key,body);if(old.isPresent())return old.get();
        var s=signing(c,true);if(edit&&(s.getAppointmentRef()==null||body.reason()==null||body.reason().isBlank()))throw ApiExceptions.validation("Reschedule requires an existing appointment and reason",null);
        if(!edit&&s.getAppointmentRef()!=null)throw ApiExceptions.conflict("Appointment already exists; use reschedule");
        if(s.getArrivalRef()!=null)throw ApiExceptions.conflict("Arrival recorded; facility incident review is required to reschedule");
        var slot=slot(c.renewal(),body.appointmentAt(),s.getEffectiveSigningDeadline());
        calendar().reserve(c.renewal(),slot,now());
        var e=event(c,"APPOINTMENT",slot);s.appointment(e.getId(),slot.start(),slot.end());c.renewal().setStatus(RenewalStatus.appointment_scheduled);
        return finish(c,op,key,body,e,200);
    }
    public Result arrival(ActorPrincipal actor,UUID id,Arrival body,String key) {
        var c=context(actor,id,Audience.STAFF,Capability.ARRIVAL,key,"d3_arrival",body,body.expectedVersion());var old=replay(c,"d3_arrival",key,body);if(old.isPresent())return old.get();
        var s=signing(c,true);currentAppointment(c,s,body.appointmentRef());
        if(s.getArrivalRef()!=null)throw ApiExceptions.conflict("Arrival already recorded for this appointment");
        evidence(actor,c.renewal(),"ARRIVAL",body.evidenceFileIds());
        var e=event(c,"ARRIVAL",Map.of("appointmentRef",body.appointmentRef(),"evidenceFileIds",body.evidenceFileIds()));s.arrival(e.getId());
        return finish(c,"d3_arrival",key,body,e,201);
    }
    public Result incident(ActorPrincipal actor,UUID id,Incident body,String key) {
        var c=context(actor,id,Audience.STAFF,Capability.INCIDENT,key,"d3_incident",body,body.expectedVersion());var old=replay(c,"d3_incident",key,body);if(old.isPresent())return old.get();
        linked(c.renewal(),body.appointmentRef(),"APPOINTMENT");
        evidence(actor,c.renewal(),"INCIDENT",body.evidenceFileIds());
        return finish(c,"d3_incident",key,body,event(c,"INCIDENT",body),201);
    }
    @Transactional(readOnly=true)
    public Statement statement(ActorPrincipal actor,UUID id) {
        var n=visible(actor,id,Audience.STAFF,false,Capability.CASH);var wf=workflow(n);
        var s=state(n);if(s==null||s.getPhase()!=RenewalOperationState.Phase.SIGNING)throw ApiExceptions.conflict("Renewal is not in signing phase");
        beforeOrEqual(now(),s.getEffectiveSigningDeadline());
        var result=accounting().statement(n,terms(new Context(n,null,wf)),now());validateStatement(n,result);return result;
    }
    public Result cash(ActorPrincipal actor,UUID id,Cash body,String key) {
        var c=context(actor,id,Audience.STAFF,Capability.CASH,key,"d3_cash",body,body.expectedVersion());var old=replay(c,"d3_cash",key,body);if(old.isPresent())return old.get();
        signing(c,true);if(!Boolean.TRUE.equals(body.received()))throw ApiExceptions.validation("Cash received attestation required",null);
        var finance=accounting();var statement=finance.statement(c.renewal(),terms(c),now());validateStatement(c.renewal(),statement);
        if(statement.amount().signum()==0)throw ApiExceptions.conflict("Required obligations are already paid; no new CASH receipt allowed");
        if(!body.payableStatementRef().equals(statement.reference()))throw ApiExceptions.conflict("Payable statement is stale");
        var receipt=finance.cash(c.renewal(),body.payableStatementRef(),body.receiptReference(),actor.userId(),key,now());
        if(receipt==null||receipt.reference()==null||!id.equals(receipt.renewalId())||!statement.reference().equals(receipt.statementRef())||receipt.amount()==null||receipt.amount().compareTo(statement.amount())!=0||!statement.currency().equals(receipt.currency())||receipt.receivedAt()==null||receipt.receivedAt().isAfter(now()))throw deferred("Shared CASH receipt is incomplete or inconsistent");
        return finish(c,"d3_cash",key,body,event(c,"CASH",receipt),201);
    }
    public Result complete(ActorPrincipal actor,UUID id,Completion body,String key) {
        var c=context(actor,id,Audience.STAFF,Capability.COMPLETE,key,"d3_complete",body,body.expectedVersion());var old=replay(c,"d3_complete",key,body);if(old.isPresent())return old.get();
        var s=signing(c,true);
        if(!Boolean.TRUE.equals(body.identityVerified()))throw ApiExceptions.validation("Identity verification attestation required",null);
        if(!Objects.equals(s.getArrivalRef(),body.arrivalRef()))throw ApiExceptions.conflict("Current server-recorded arrival required");
        var arrival=linked(c.renewal(),body.arrivalRef(),"ARRIVAL");
        if(arrival.getOccurredAt().isAfter(now())||arrival.getOccurredAt().isAfter(s.getEffectiveSigningDeadline()))throw ApiExceptions.conflict("Arrival outside deadline");
        currentAppointment(c,s,s.getAppointmentRef());evidence(actor,c.renewal(),"SIGNED_RENEWAL",List.of(body.signedDocumentFileId()));
        var terms=terms(c);accounting().requireFullyPaid(c.renewal(),terms,now());
        var safety=safety();var finalCutoff=safety.check(c.renewal(),terms,c.workflow().getExtensionHoldRef(),now());
        if(finalCutoff==null)throw deferred("Recovery cutoff unavailable at completion");
        beforeOrEqual(now(),s.getEffectiveSigningDeadline());beforeOrEqual(now(),min(s.getRecoveryCutoff(),finalCutoff));
        safety.consume(c.renewal(),c.workflow().getExtensionHoldRef());
        Instant completedAt=now();beforeOrEqual(completedAt,s.getEffectiveSigningDeadline());beforeOrEqual(completedAt,min(s.getRecoveryCutoff(),finalCutoff));
        // Normal completion is the only D3 command that extends Rental; shared writer locks are mandatory above.
        c.renewal().getRental().setContractEndDate(terms.newEndDate());c.renewal().setStatus(RenewalStatus.completed);
        s.completed(actor.userId(),completedAt);store.releaseSlot(c.workflow());
        return finish(c,"d3_complete",key,body,event(c,"COMPLETION",body),200);
    }
    public Result exception(ActorPrincipal actor,UUID id,ExceptionDecision body,String key) {
        var c=context(actor,id,Audience.MANAGER,Capability.EXCEPTION,key,"d3_exception",body,body.expectedVersion());var old=replay(c,"d3_exception",key,body);if(old.isPresent())return old.get();
        linked(c.renewal(),body.incidentId(),"INCIDENT");evidence(actor,c.renewal(),"EXCEPTION",body.evidenceFileIds());
        if(body.action()==ExceptionAction.APPROVE_RESCHEDULE_BEFORE_CUTOFF) {
            nonterminal(c.renewal());var s=requiredState(c.renewal());var p=policy(c.renewal());
            policies.getIfAvailable().requireFacilityFault(c.renewal(),body.incidentId(),actor.userId());
            if(s.getDepositPaidAt()==null||s.getPhase()==RenewalOperationState.Phase.PAYMENT_EXPIRED)throw ApiExceptions.conflict("Verified deposit required");
            var cutoff=safety().check(c.renewal(),terms(c),c.workflow().getExtensionHoldRef(),now());
            if(body.revisedDeadline()==null||body.appointmentAt()==null||cutoff==null||!now().isBefore(cutoff)||body.revisedDeadline().isAfter(cutoff)||body.revisedDeadline().isBefore(s.getEffectiveSigningDeadline())||body.revisedDeadline().isAfter(s.getOriginalSigningDeadline().plus(p.exceptionExtensionLimit())))throw ApiExceptions.conflict("Exception deadline outside BO bounds/cutoff");
            slot(c.renewal(),body.appointmentAt(),body.revisedDeadline());
        } else if(body.appointmentAt()!=null||body.revisedDeadline()!=null)throw ApiExceptions.validation("Only reschedule decision accepts appointment/deadline",null);
        // Proposal only: no change to deadline/hold until Customer confirmation.
        var decisionEvent=event(c,"EXCEPTION",body);var state=state(c.renewal());if(state!=null)state.proposedException(decisionEvent.getId());
        return finish(c,"d3_exception",key,body,decisionEvent,201);
    }
    public Result confirm(ActorPrincipal actor,UUID id,Confirmation body,String key) {
        var c=context(actor,id,Audience.CUSTOMER,Capability.READ,key,"d3_confirm",body,body.expectedVersion());var old=replay(c,"d3_confirm",key,body);if(old.isPresent())return old.get();
        nonterminal(c.renewal());var s=requiredState(c.renewal());var event=linked(c.renewal(),body.decisionRef(),"EXCEPTION");var decision=decode(event.getPayloadJson(),ExceptionDecision.class);
        if(!Objects.equals(s.getPendingExceptionRef(),body.decisionRef()))throw ApiExceptions.conflict("Decision already confirmed or superseded");
        var valid=validateReschedule(c,s,event,decision);
        safety().retain(c.renewal(),c.workflow().getExtensionHoldRef(),decision.revisedDeadline());
        calendar().reserve(c.renewal(),valid.slot(),now());
        Instant confirmedAt=now();
        if(!confirmedAt.isBefore(valid.cutoff())||!confirmedAt.isBefore(valid.slot().start())||!confirmedAt.isBefore(decision.revisedDeadline()))throw ApiExceptions.conflict("Reschedule expired during reservation");
        var appointment=event(c,"APPOINTMENT",valid.slot());s.exception(body.decisionRef(),decision.revisedDeadline(),valid.cutoff(),appointment.getId(),valid.slot().start(),valid.slot().end());c.renewal().setStatus(RenewalStatus.appointment_scheduled);
        return finish(c,"d3_confirm",key,body,event(c,"CONFIRMATION",Map.of("decisionRef",body.decisionRef(),"appointmentRef",appointment.getId())),200);
    }
    private record ValidReschedule(Slot slot,Instant cutoff) {}
    private void requireProposalState(RenewalOperationState s,RenewalOperationEvent event) {
        if(s.getDepositPaidAt()==null||s.getOriginalSigningDeadline()==null||s.getEffectiveSigningDeadline()==null||s.getPhase()==null
            ||!Set.of(RenewalOperationState.Phase.SIGNING,RenewalOperationState.Phase.SIGNING_EXPIRED).contains(s.getPhase())
            ||event.getOccurredAt()==null||event.getOccurredAt().isAfter(now()))throw ApiExceptions.conflict("Signing proposal state is no longer valid");
    }
    private ValidReschedule validateReschedule(Context c,RenewalOperationState s,RenewalOperationEvent event,ExceptionDecision decision) {
        requireProposalState(s,event);
        if(decision.action()!=ExceptionAction.APPROVE_RESCHEDULE_BEFORE_CUTOFF||decision.appointmentAt()==null||decision.revisedDeadline()==null)throw ApiExceptions.conflict("Decision is not a signing reschedule");
        linked(c.renewal(),decision.incidentId(),"INCIDENT");var p=policy(c.renewal());
        var cutoff=safety().check(c.renewal(),terms(c),c.workflow().getExtensionHoldRef(),now());
        policies.getIfAvailable().requireFacilityFault(c.renewal(),decision.incidentId(),event.getActorId());
        if(cutoff==null||!now().isBefore(cutoff)||decision.revisedDeadline().isAfter(cutoff)||decision.revisedDeadline().isBefore(s.getEffectiveSigningDeadline())||decision.revisedDeadline().isAfter(s.getOriginalSigningDeadline().plus(p.exceptionExtensionLimit())))throw ApiExceptions.conflict("Reschedule no longer eligible");
        return new ValidReschedule(slot(c.renewal(),decision.appointmentAt(),decision.revisedDeadline()),cutoff);
    }
    public Result refund(ActorPrincipal actor,UUID id,RefundDecision body,String key) {
        var c=context(actor,id,Audience.MANAGER,Capability.REFUND,key,"d3_refund",body,body.expectedVersion());var old=replay(c,"d3_refund",key,body);if(old.isPresent())return old.get();
        linked(c.renewal(),body.incidentId(),"INCIDENT");evidence(actor,c.renewal(),"REFUND",body.evidenceFileIds());
        Map<String,Object> data=new LinkedHashMap<>();data.put("decision",body.decision());data.put("incidentId",body.incidentId());data.put("reason",body.reason());data.put("evidenceFileIds",body.evidenceFileIds());
        if(body.decision()==RenewalCommands.DecisionType.APPROVE) {
            var source=refunds.getIfAvailable();if(source==null||!source.atomic())throw deferred("Refund entitlement/reserve/calendar source unavailable");
            var reserved=source.reserve(c.renewal(),body.incidentId(),actor.userId(),key,now());
            if(reserved==null||reserved.reference()==null||!id.equals(reserved.renewalId())||reserved.amount()==null||reserved.amount().signum()<=0||!"VND".equals(reserved.currency())||reserved.reviewDueAt()==null||reserved.executionDueAt()==null)throw deferred("Refund reservation incomplete");
            data.put("status","APPROVED_AWAITING_EXECUTION");data.put("reservation",reserved);
        } else data.put("status","REJECTED");
        return finish(c,"d3_refund",key,body,event(c,"REFUND",data),201);
    }
    /** Internal per-resource reconciliation entry; no auto scheduler is enabled before sources/schema rollout. */
    public boolean expire(UUID id) {
        var n=em.find(Renewal.class,id);if(n==null)return false;
        var rental=store.lockRental(n.getRental().getId());em.refresh(rental);em.refresh(n);var wf=workflow(n);em.lock(wf,LockModeType.PESSIMISTIC_WRITE);em.refresh(wf);
        var s=state(n);boolean paid=s!=null&&s.getDepositPaidAt()!=null;
        if(Set.of(RenewalStatus.cancelled,RenewalStatus.rejected,RenewalStatus.completed).contains(n.getStatus())||s!=null&&s.getPhase()!=RenewalOperationState.Phase.SIGNING)return false;
        Instant deadline=paid?s.getEffectiveSigningDeadline():wf.getPaymentDeadline();if(deadline==null||!now().isAfter(deadline))return false;
        if(!accounting().safeToExpire(n))throw deferred("Payment attempts require reconciliation before expiry");
        var safety=safety();if(wf.getExtensionHoldRef()==null)throw deferred("Extension hold missing");safety.release(n,wf.getExtensionHoldRef());
        if(s==null){s=new RenewalOperationState(n);em.persist(s);}s.expire(paid);
        if(!paid)n.setStatus(RenewalStatus.payment_expired); // Paid signing expiry stays supplemental, not payment failure.
        var event=new RenewalOperationEvent(n,"EXPIRY",now(),null,store.canonical(Map.of("disposition","REVIEW_REQUIRED","paid",paid)));em.persist(event);
        em.lock(wf,LockModeType.PESSIMISTIC_FORCE_INCREMENT);em.flush();audit.recordMutation((User)null,"renewal_expiry","Renewal",id,rental.getFacility().getId(),null,Map.of("eventId",event.getId()));
        // No forfeiture, physical-unit release, recovery pause or slot release without reconciliation contract.
        return true;
    }
    @Transactional(readOnly=true)
    public PageResponse<RenewalOperationResponse> appointments(ActorPrincipal actor,UUID facilityId,LocalDate date,String phase,int page,int size,String correlation) {
        if(actor==null)throw ApiExceptions.unauthorized("Authentication required");if(!actor.hasRole(RoleCode.STAFF))throw ApiExceptions.forbidden("Staff role required");
        if(page<0||size<1||size>100||(long)page*size>Integer.MAX_VALUE||phase!=null&&!Set.of("SIGNING","SIGNING_EXPIRED","COMPLETED").contains(phase))throw ApiExceptions.validation("Invalid appointment query",null);
        var scopeIds=actor.facilityScopes().entrySet().stream().filter(e->e.getValue()!=null&&e.getValue().includes(FacilityScopeLevel.OPERATE)).map(Map.Entry::getKey).toList();if(scopeIds.isEmpty())throw ApiExceptions.forbidden("Staff OPERATE scope required");if(facilityId!=null&&!scopeIds.contains(facilityId))throw ApiExceptions.forbidden("Staff OPERATE scope required");
        var auth=authorizations.getIfAvailable();if(auth==null)throw deferred("Staff signing/assignment query source missing");var ids=auth.assignedRenewalIds(actor);if(ids==null||ids.stream().anyMatch(Objects::isNull))throw deferred("Staff signing/assignment query incomplete");
        var pageable=PageRequest.of(page,size,Sort.by("appointmentStart").and(Sort.by("id")));if(ids.isEmpty())return PageResponse.from(Page.empty(pageable),correlation);
        String filter=" where s.appointmentRef is not null and s.id in :ids and s.renewal.rental.facility.id in :facilities"+(facilityId==null?"":" and s.renewal.rental.facility.id=:facility")+(phase==null?"":" and s.phase=:phase")+(date==null?"":" and s.appointmentStart>=:start and s.appointmentStart<:end");
        var query=em.createQuery("select s from RenewalOperationState s"+filter+" order by s.appointmentStart,s.id",RenewalOperationState.class);var count=em.createQuery("select count(s) from RenewalOperationState s"+filter,Long.class);
        for(var q:List.of(query,count)){q.setParameter("ids",ids);q.setParameter("facilities",scopeIds);if(facilityId!=null)q.setParameter("facility",facilityId);if(phase!=null)q.setParameter("phase",RenewalOperationState.Phase.valueOf(phase));if(date!=null){var zone=ZoneId.of("Asia/Ho_Chi_Minh");q.setParameter("start",date.atStartOfDay(zone).toInstant());q.setParameter("end",date.plusDays(1).atStartOfDay(zone).toInstant());}}
        var rows=query.setFirstResult(page*size).setMaxResults(size).getResultList().stream().map(s->{auth.require(actor,s.getRenewal(),Capability.READ);return projection(s.getRenewal());}).toList();return PageResponse.from(new PageImpl<>(rows,pageable,count.getSingleResult()),correlation);
    }
    @Transactional(readOnly=true)
    public PageResponse<RenewalOperationResponse.Event> events(ActorPrincipal actor,UUID id,Audience audience,String category,int page,int size,String correlation) {
        var n=visible(actor,id,audience,false,Capability.READ);if(page<0||size<1||size>100)throw ApiExceptions.validation("Invalid page/size",null);
        List<String> kinds=switch(category){case "payments"->List.of("DEPOSIT","CASH");case "refunds"->List.of("REFUND");case "facility-incidents"->List.of("INCIDENT","EXCEPTION");default->throw ApiExceptions.validation("Unknown event category",null);};
        if(category.equals("facility-incidents")&&audience!=Audience.MANAGER)throw ApiExceptions.forbidden("Internal incident timeline");
        var q=em.createQuery("select e from RenewalOperationEvent e where e.renewal.id=:id and e.kind in :kinds order by e.occurredAt desc,e.id",RenewalOperationEvent.class).setParameter("id",n.getId()).setParameter("kinds",kinds);
        long count=em.createQuery("select count(e) from RenewalOperationEvent e where e.renewal.id=:id and e.kind in :kinds",Long.class).setParameter("id",id).setParameter("kinds",kinds).getSingleResult();
        var rows=q.setFirstResult(Math.multiplyExact(page,size)).setMaxResults(size).getResultList().stream().map(e->eventResponse(e,audience)).toList();
        return PageResponse.from(new PageImpl<>(rows,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"occurredAt").and(Sort.by("id"))),count),correlation);
    }
    private Context context(ActorPrincipal a,UUID id,Audience audience,Capability capability,String key,String op,Object body,Long version) {
        var n=visible(a,id,audience,true,capability);var user=em.find(User.class,a.userId(),LockModeType.PESSIMISTIC_WRITE);if(user==null)throw ApiExceptions.unauthorized("Actor no longer exists");
        var rental=store.lockRental(n.getRental().getId());em.refresh(rental);em.refresh(n);visible(a,id,audience,true,capability);
        // Replay before state/version checks, but only after current resource authorization.
        if(store.replay(user,op,key,id,body).isPresent())return new Context(n,user,workflow(n));
        if(version==null||version<0)throw ApiExceptions.validation("expectedVersion required",null);
        return new Context(n,user,store.lockWorkflow(id,version));
    }
    private Renewal visible(ActorPrincipal a,UUID id,Audience audience,boolean write,Capability capability) {
        if(a==null)throw ApiExceptions.unauthorized("Authentication required");
        if(audience==Audience.CUSTOMER)RenewalReadService.authorize(a,false,false);
        else if(audience==Audience.MANAGER)RenewalReadService.authorize(a,true,write);
        else if(!a.hasRole(RoleCode.STAFF))throw ApiExceptions.forbidden("Staff role required");
        var n=em.find(Renewal.class,id);if(n==null||n.getRental()==null)throw ApiExceptions.notFound("Renewal not found");var r=n.getRental();
        if(audience==Audience.CUSTOMER){if(r.getCustomer()==null||!r.getCustomer().getId().equals(a.userId()))throw ApiExceptions.notFound("Renewal not found");}
        else {var level=audience==Audience.STAFF?FacilityScopeLevel.OPERATE:(write?FacilityScopeLevel.MANAGE:FacilityScopeLevel.READ);var scope=a.facilityScopes().get(r.getFacility().getId());if(scope==null||!scope.includes(level))throw ApiExceptions.notFound("Renewal not found");}
        if(audience==Audience.STAFF||capability==Capability.EXCEPTION||capability==Capability.REFUND){var auth=authorizations.getIfAvailable();if(auth==null)throw deferred("Signing/assignment/specialized permission contract unavailable");auth.require(a,n,capability);}
        return n;
    }
    private RenewalOperationState signing(Context c,boolean safetyCheck) {
        nonterminal(c.renewal());var s=requiredState(c.renewal());
        if(s.getPhase()!=RenewalOperationState.Phase.SIGNING||s.getDepositPaidAt()==null)throw ApiExceptions.conflict("Verified deposit and active signing phase required");
        beforeOrEqual(now(),s.getEffectiveSigningDeadline());beforeOrEqual(now(),s.getRecoveryCutoff());
        if(safetyCheck){var cutoff=safety().check(c.renewal(),terms(c),c.workflow().getExtensionHoldRef(),now());if(cutoff==null)throw deferred("Recovery cutoff unavailable");beforeOrEqual(now(),cutoff);}
        return s;
    }
    private static void nonterminal(Renewal n) {if(Set.of(RenewalStatus.cancelled,RenewalStatus.rejected,RenewalStatus.completed,RenewalStatus.payment_expired).contains(n.getStatus()))throw ApiExceptions.conflict("Terminal Renewal cannot resume");}
    private RenewalOperationState state(Renewal n){return em.find(RenewalOperationState.class,n.getId());}
    private RenewalOperationState requiredState(Renewal n){var s=state(n);if(s==null)throw deferred("Verified D3 state missing");return s;}
    private RenewalWorkflow workflow(Renewal n){var w=em.find(RenewalWorkflow.class,n.getId());if(w==null)throw ApiExceptions.conflict("Verified accepted workflow missing");return w;}
    private RenewalQuoteResponse.Terms terms(Context c) {
        var rev=c.workflow().getAcceptedRevision();var quote=rev.getQuote();var n=c.renewal();var r=n.getRental();var t=decode(quote.getTermsJson(),RenewalQuoteResponse.Terms.class);
        if(!rev.getRenewal().getId().equals(n.getId())||!quote.getRental().getId().equals(r.getId())||!quote.getCustomer().getId().equals(r.getCustomer().getId())||!Objects.equals(n.getRequestedBy().getId(),r.getCustomer().getId())||t.totalAfterDiscount()==null||n.getAmount().compareTo(t.totalAfterDiscount())!=0||!Objects.equals(n.getNewEndDate(),t.newEndDate())||!Objects.equals(r.getContractEndDate(),t.oldEndDate())||!Objects.equals(r.getStorageUnit().getId(),t.storageUnitId())||r.getStorageUnit().getUnitType()==null||!Objects.equals(r.getStorageUnit().getUnitType().getId(),t.unitTypeId())||!Objects.equals(r.getFacility().getId(),t.facilityId())||r.getStatus()!=RentalStatus.active||r.getActualReturnedAt()!=null)throw ApiExceptions.conflict("Rental/accepted terms or return state changed");
        if(t.oldEndDate()==null||t.newEndDate()==null||!t.oldEndDate().plusDays(1).equals(t.extensionStartDate())||!t.newEndDate().plusDays(1).equals(t.extensionEndExclusive())||!t.newEndDate().isAfter(t.oldEndDate())||t.renewalDepositAmount()==null||t.renewalDepositAmount().signum()<0||t.remainingRentalAmount()==null||t.remainingRentalAmount().signum()<0||t.renewalDepositAmount().add(t.remainingRentalAmount()).compareTo(t.totalAfterDiscount())!=0||!"VND".equals(t.currency()))throw ApiExceptions.conflict("Accepted period/financial terms inconsistent");
        return t;
    }
    private void currentAppointment(Context c,RenewalOperationState s,UUID ref){if(ref==null||!ref.equals(s.getAppointmentRef()))throw ApiExceptions.conflict("Current appointment required");linked(c.renewal(),ref,"APPOINTMENT");}
    private RenewalOperationEvent linked(Renewal n,UUID ref,String kind){var e=ref==null?null:em.find(RenewalOperationEvent.class,ref);if(e==null||!n.getId().equals(e.getRenewal().getId())||!kind.equals(e.getKind()))throw ApiExceptions.notFound("Related event not found");return e;}
    private Policy policy(Renewal n){var src=policies.getIfAvailable();var p=src==null?null:src.read(n).orElse(null);if(p==null||p.reference()==null||p.reference().isBlank()||p.version()==null||p.version().isBlank()||p.signingWindow()==null||p.signingWindow().isNegative()||p.signingWindow().isZero()||p.exceptionExtensionLimit()==null||p.exceptionExtensionLimit().isNegative())throw deferred("BO signing/exception policy missing");return p;}
    private CalendarSource calendar(){var s=calendars.getIfAvailable();if(s==null||!s.atomic())throw deferred("Transactional service calendar/office hours missing");return s;}
    private Slot slot(Renewal n,Instant at,Instant deadline){if(at==null||at.isBefore(now()))throw ApiExceptions.conflict("Appointment must not be in the past");var s=calendar().slot(n,at).orElseThrow(()->ApiExceptions.conflict("Service slot unavailable"));if(s.reference()==null||s.reference().isBlank()||!at.equals(s.start())||s.end()==null||!s.end().isAfter(s.start())||s.end().isAfter(deadline))throw ApiExceptions.conflict("Entire service slot must fit deadline");return s;}
    private SafetySource safety(){var s=safetySources.getIfAvailable();if(s==null||!s.atomic())throw deferred("Shared transactional capacity/return/recovery safety missing");return s;}
    private AccountingSource accounting(){var s=accountingSources.getIfAvailable();if(s==null||!s.atomic())throw deferred("Shared transactional Renewal accounting missing");return s;}
    private void evidence(ActorPrincipal a,Renewal n,String purpose,List<UUID> ids){if(ids==null||ids.size()>10||ids.stream().anyMatch(Objects::isNull)||new HashSet<>(ids).size()!=ids.size())throw ApiExceptions.validation("Invalid evidence IDs",null);var s=evidences.getIfAvailable();if(s==null)throw deferred("Resource-linked evidence authorization missing");s.require(a,n,purpose,ids);}
    private void validateStatement(Renewal n,Statement s){if(s==null||s.reference()==null||!n.getId().equals(s.renewalId())||s.version()==null||s.version().isBlank()||s.expiresAt()==null||!now().isBefore(s.expiresAt())||s.amount()==null||s.amount().signum()<0||!"VND".equals(s.currency())||s.requiredObligations()==null||s.requiredObligations().isEmpty()||s.requiredObligations().stream().anyMatch(Objects::isNull)||new HashSet<>(s.requiredObligations()).size()!=s.requiredObligations().size())throw deferred("Payable statement incomplete/stale");}
    private RenewalOperationEvent event(Context c,String kind,Object data){var e=new RenewalOperationEvent(c.renewal(),kind,now(),c.user().getId(),store.canonical(data));em.persist(e);em.flush();return e;}
    private Result finish(Context c,String op,String key,Object body,RenewalOperationEvent e,int status){em.lock(c.workflow(),LockModeType.PESSIMISTIC_FORCE_INCREMENT);em.flush();var result=new Result(projection(c.renewal()),eventResponse(e,Audience.MANAGER));audit.recordMutation(c.user(),op,"Renewal",c.renewal().getId(),c.renewal().getRental().getFacility().getId(),null,Map.of("eventId",e.getId(),"state",result.state()));store.remember(c.user(),op,key,c.renewal().getId(),body,status,result);return result;}
    private Optional<Result> replay(Context c,String op,String key,Object body){return store.replay(c.user(),op,key,c.renewal().getId(),body).map(r->decode(r.dataJson(),Result.class));}
    private RenewalOperationResponse projection(Renewal n) {
        var w=em.find(RenewalWorkflow.class,n.getId());var s=state(n);var missing=new ArrayList<String>();
        var policy=policies.getIfAvailable();if(policy==null||policy.read(n).isEmpty())missing.add("BO_SIGNING_POLICY");
        var calendar=calendars.getIfAvailable();if(calendar==null||!calendar.atomic())missing.add("SERVICE_CALENDAR");
        var accounting=accountingSources.getIfAvailable();if(accounting==null||!accounting.atomic())missing.add("RENEWAL_ACCOUNTING");
        var safety=safetySources.getIfAvailable();if(safety==null||!safety.atomic())missing.add("SHARED_HOLD_RETURN_RECOVERY");
        if(authorizations.getIfAvailable()==null)missing.add("STAFF_PERMISSION_ASSIGNMENT");if(evidences.getIfAvailable()==null)missing.add("RESOURCE_EVIDENCE");
        var refund=refunds.getIfAvailable();if(refund==null||!refund.atomic())missing.add("REFUND_ENTITLEMENT_EXECUTION");
        String phase=s==null?(w!=null&&n.getStatus()==RenewalStatus.approved?"AWAITING_DEPOSIT":"UNKNOWN"):s.getPhase().name();
        if(s!=null&&s.getPhase()==RenewalOperationState.Phase.SIGNING&&s.getEffectiveSigningDeadline()!=null&&now().isAfter(s.getEffectiveSigningDeadline()))phase="SIGNING_EXPIRY_PENDING";
        return new RenewalOperationResponse(n.getId(),w==null?null:w.getVersion(),phase,
            s==null?null:s.getDepositPaymentRef(),s==null?null:s.getDepositPaidAt(),s==null?null:s.getOriginalSigningDeadline(),
            s==null?null:s.getEffectiveSigningDeadline(),s==null?null:s.getRecoveryCutoff(),s==null?null:s.getAppointmentRef(),
            s==null?null:s.getArrivalRef(),s==null?null:s.getConfirmedExceptionRef(),s==null?null:s.getCompletedBy(),s==null?null:s.getCompletedAt(),
            missing,s==null?null:s.getAppointmentStart(),s==null?null:s.getAppointmentEnd(),s==null?null:s.getPendingExceptionRef());
    }
    private RenewalOperationResponse.Event eventResponse(RenewalOperationEvent e,Audience audience){Object data=decode(e.getPayloadJson(),Object.class);if(audience==Audience.CUSTOMER&&e.getKind().equals("REFUND")){var raw=mapper.valueToTree(data);var safe=new LinkedHashMap<String,Object>();safe.put("status",raw.path("status").asText());if(raw.has("reservation"))safe.put("reservation",mapper.convertValue(raw.get("reservation"),Object.class));data=safe;}return new RenewalOperationResponse.Event(e.getId(),e.getKind(),e.getOccurredAt(),audience==Audience.CUSTOMER?null:e.getActorId(),data);}
    private <T>T decode(String json,Class<T> type){try{return mapper.readValue(json,type);}catch(Exception e){throw ApiExceptions.conflict("Stored operation snapshot invalid");}}
    private Instant now(){var c=clocks.getIfAvailable();return (c==null?Clock.systemUTC():c).instant();}
    private static Instant min(Instant a,Instant b){return a.isBefore(b)?a:b;}
    private static void beforeOrEqual(Instant now,Instant end){if(end==null||now.isAfter(end))throw ApiExceptions.conflict("Operation deadline has passed");}
    private static RuntimeException deferred(String reason){return ApiExceptions.conflict("DEFERRED_SOURCE: "+reason);}
}
