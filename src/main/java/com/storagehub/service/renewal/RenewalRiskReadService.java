package com.storagehub.service.renewal;

import com.storagehub.time.BusinessTime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.api.renewal.RenewalQuoteResponse;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.RentalQueryService;
import com.storagehub.service.overdue.*;
import com.storagehub.service.renewal.operations.*;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class RenewalRiskReadService {
    public record View(UUID rentalId,UUID renewalId,Instant checkedAt,String completeness,Long overdueDays,
        String severity,Instant signingDeadline,Instant repossessionDeadline,Instant repossessionStartsAt,
        Boolean withinOrdinaryRenewalWindow,BigDecimal remainingRentalAmount,BigDecimal estimatedOverdueFee,String currency,
        String repossessionStatus,Instant repossessionStartedAt,UUID recoveryTaskId) {}
    private final EntityManager em;
    private final RentalQueryService rentals;
    private final RenewalOperationService operations;
    private final ObjectProvider<OverdueSources.TermSource> terms;
    private final ObjectProvider<Clock> clocks;
    private final ObjectMapper mapper;
    private final com.storagehub.service.renewal.integration.RenewalPolicyPublicationService policies;
    private final ObjectProvider<RecoveryTaskReadService> recoveryTasks;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.storagehub.service.ledger.RenewalOverdueFeeService> overdueFees;

    public View rental(ActorPrincipal actor,UUID id,boolean manager) {
        rentals.detail(actor,id,manager); // Reuse resource ownership/scope authorization.
        return project(em.find(Rental.class,id),null,null);
    }
    public View renewal(ActorPrincipal actor,UUID id,RenewalOperationService.Audience audience) {
        operations.detail(actor,id,audience);
        var renewal=em.find(Renewal.class,id);
        var state=em.find(RenewalOperationState.class,id);
        return project(renewal.getRental(),renewal,state!=null&&state.hasVerifiedPayment()?state.getEffectiveSigningDeadline():null);
    }
    private View project(Rental rental,Renewal renewal,Instant signing) {
        var clock=clocks.getIfAvailable();
        Instant now=(clock==null?Clock.systemUTC():clock).instant();
        var source=terms.getIfAvailable();var term=source==null?null:source.term(rental,now).orElse(null);
        UUID id=renewal==null?null:renewal.getId();
        var taskReader=recoveryTasks.getIfAvailable();
        var task=taskReader==null?null:taskReader.forRental(rental.getId()).orElse(null);
        if(task!=null&&(!task.rentalId().equals(rental.getId())||!task.facilityId().equals(rental.getFacility().getId())))
            throw ApiExceptions.conflict("Recovery task facility mismatch");
        if(task!=null&&(task.id()==null||task.receivedAt()==null||task.receivedAt().isAfter(now)))
            throw ApiExceptions.conflict("Recovery task receipt is invalid");
        String recoveryStatus=task==null?"NOT_CONFIRMED":switch(task.status()) {
            case "OPEN","IN_PROGRESS"->"REPOSSESSION_STARTED";
            case "CLOSED"->"REPOSSESSION_CLOSED";
            default->throw ApiExceptions.conflict("Recovery task status is invalid");
        };
        Instant startedAt=task==null?null:task.receivedAt();
        UUID taskId=task==null?null:task.id();
        BigDecimal remainder=null;
        if(renewal!=null) {
            var workflow=em.find(RenewalWorkflow.class,id);
            if(workflow!=null)try {
                remainder=mapper.readValue(workflow.getAcceptedRevision().getQuote().getTermsJson(),RenewalQuoteResponse.Terms.class).dueAtSigning();
            }catch(Exception invalid){throw ApiExceptions.conflict("Accepted financial terms invalid");}
        }
        if(term==null)return new View(rental.getId(),id,now,"UNKNOWN",null,"UNKNOWN",signing,null,null,
            task==null?null:false,remainder,null,"VND",recoveryStatus,startedAt,taskId);
        // The term provider owns normalization of inclusive/exclusive Rental dates and timezone.
        ZoneId zone=BusinessTime.ZONE; // Both published and catalog policies use the fixed Vietnam overdue calendar.
        long days=RenewalOverdueTimeline.overdueDays(term.lastPermittedDate(),now,zone);
        boolean allowed=task==null&&rental.getStatus()==RentalStatus.active&&rental.getActualReturnedAt()==null&&now.isBefore(term.recoveryCutoff());
        BigDecimal fee=null;var feeSource=overdueFees==null?null:overdueFees.getIfAvailable();
        if(feeSource!=null)try{fee=feeSource.estimateAtSigning(rental,now);}catch(com.storagehub.common.api.ApiException unavailable){/* Keep the deadline warning visible when fee policy is unavailable. */}
        return new View(rental.getId(),id,now,"COMPLETE",days,RenewalOverdueTimeline.severity(days,now,term.recoveryCutoff()),
            signing,term.recoveryCutoff(),term.recoveryStart(),allowed,remainder,fee,"VND",recoveryStatus,startedAt,taskId);
    }
}
