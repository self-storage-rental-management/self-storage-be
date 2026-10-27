package com.storagehub.service.renewal.integration;

import com.storagehub.time.BusinessTime;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.service.overdue.OverdueSources;
import com.storagehub.service.rental.period.RentalPeriodResolver;
import com.storagehub.service.renewal.RenewalSources;
import com.storagehub.service.renewal.operations.RenewalOperationSources;
import com.storagehub.service.renewal.operations.RenewalOperationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @RequiredArgsConstructor @Transactional(readOnly=true)
public class PublishedRenewalPolicyAdapters implements RenewalSources.PolicySource,RenewalOperationSources.PolicySource,OverdueSources.TermSource {
    @org.springframework.beans.factory.annotation.Autowired
    private EffectiveRenewalPolicySource effectivePolicies;
    private final RenewalPolicyPublicationService policies;
    private final RentalPeriodResolver periods;
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final com.storagehub.service.integration.DuongResourceAccess access;
    public Optional<RenewalSources.Policy> read(Rental r,LocalDate start) {
        if(r==null||r.getFacility()==null)return Optional.empty();
        return effective(r,start).map(p -> new RenewalSources.Policy(p.reference(),p.version(),Duration.ZERO,p.paymentWindow(),0,p.depositRate(),p.eligiblePackageIds()));
    }
    public Optional<RenewalOperationSources.Policy> read(Renewal n) {
        if(n==null||n.getRental()==null||n.getRental().getFacility()==null)return Optional.empty();
        var period=periods.read(n.getRental());if(period.isEmpty())return Optional.empty();
        return effective(n.getRental(),period.get().endExclusive()).filter(s->s.signing()!=null)
            .map(s->new RenewalOperationSources.Policy(s.reference(),s.version(),Duration.ofMinutes(s.signing().signingWindowMinutes()),Duration.ofMinutes(s.signing().exceptionExtensionLimitMinutes())));
    }
    public Optional<OverdueSources.Term> term(Rental r,Instant now) {
        if(r==null||r.getFacility()==null||now==null)return Optional.empty();
        var period=periods.read(r);if(period.isEmpty())return Optional.empty();
        return effective(r,now.atZone(BusinessTime.ZONE).toLocalDate()).filter(s->s.term()!=null).map(s->{
            var t=s.term();var last=period.get().lastPermittedDate();var zone=ZoneId.of(t.timezone());
            return com.storagehub.service.overdue.RenewalOverdueTimeline.term(s.reference(),s.version(),last,zone);
        });
    }
    private Optional<EffectiveRenewalPolicySource.Resolved> effective(Rental rental,LocalDate date) {
        return effectivePolicies != null ? effectivePolicies.read(rental,date)
            : policies.read(rental.getFacility().getId(),date).map(EffectiveRenewalPolicySource.Resolved::published);
    }
    public void requireFacilityFault(Renewal n,UUID incident,UUID reviewer) {
        var user=reviewer==null?null:em.find(User.class,reviewer);
        if(n==null||n.getRental()==null||n.getRental().getFacility()==null||!access.eligible(user,RoleCode.MANAGER,SystemPermission.VIEW_RENTALS,SystemPermission.MANAGE_RENTALS)
            ||!access.scope(reviewer,n.getRental().getFacility().getId(),FacilityScopeLevel.MANAGE))throw ApiExceptions.conflict("Facility fault reviewer is no longer authorized");
        var original=em.find(RenewalOperationEvent.class,incident);
        if(original==null||!"INCIDENT".equals(original.getKind())||!Objects.equals(n.getId(),original.getRenewal().getId()))throw ApiExceptions.notFound("Incident not found");
        var reviews=em.createQuery("select e from RenewalOperationEvent e where e.renewal.id=:id and e.kind='FAULT_REVIEW' order by e.occurredAt desc,e.id desc",RenewalOperationEvent.class).setParameter("id",n.getId()).getResultList();
        RenewalOperationEvent latest=null;com.fasterxml.jackson.databind.JsonNode latestData=null;
        for(var e:reviews)try {
            var d=mapper.readTree(e.getPayloadJson());
            if(d==null)throw ApiExceptions.conflict("Facility fault review is invalid");
            if(incident.toString().equals(d.path("incidentId").asText())) {
                if(latest==null){latest=e;latestData=d;}
                else if(Objects.equals(latest.getOccurredAt(),e.getOccurredAt()))throw ApiExceptions.conflict("Facility fault review is ambiguous");
                else break;
            }
        }catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw ApiExceptions.conflict("Facility fault review is invalid");}
        if(latest==null)throw ApiExceptions.conflict("DEFERRED_SOURCE: Facility fault classification is missing");
        if(!latestData.path("facilityFault").isBoolean()||!latestData.path("facilityFault").booleanValue()||!Objects.equals(latest.getActorId(),reviewer))throw ApiExceptions.conflict("Facility fault has not been verified by this reviewer");
    }
}
