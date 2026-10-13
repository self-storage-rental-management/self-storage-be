package com.storagehub.service.renewal.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.integration.DuongResourceAccess;
import com.storagehub.service.renewal.operations.*;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Facility-scoped Staff authorization. Historical assignment events are read-only metadata. */
@Component @RequiredArgsConstructor @Transactional(readOnly=true)
public class RenewalAssignmentSource implements RenewalOperationSources.AuthorizationSource {
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final DuongResourceAccess access;
    public record Assignment(UUID renewalId,UUID facilityId,UUID staffId,UUID acceptedQuoteId,long workflowRevision) {}
    public Optional<Assignment> current(Renewal n) {
        var rows=em.createQuery("select e from RenewalOperationEvent e where e.renewal.id=:id and e.kind='STAFF_ASSIGNMENT' order by e.occurredAt desc,e.id desc",RenewalOperationEvent.class).setParameter("id",n.getId()).setMaxResults(2).getResultList();
        if(rows.isEmpty())return Optional.empty();
        if(rows.size()>1&&Objects.equals(rows.get(0).getOccurredAt(),rows.get(1).getOccurredAt()))throw ApiExceptions.conflict("DEFERRED_SOURCE: Assignment evidence is ambiguous");
        try {
            var a=mapper.readValue(rows.getFirst().getPayloadJson(),Assignment.class);var w=em.find(RenewalWorkflow.class,n.getId());
            if(a==null||a.staffId()==null||!Objects.equals(a.renewalId(),n.getId())||!Objects.equals(a.facilityId(),n.getRental().getFacility().getId())
                ||w==null||w.getAcceptedRevision()==null||!Objects.equals(a.acceptedQuoteId(),w.getAcceptedRevision().getQuote().getId())||a.workflowRevision()<0||a.workflowRevision()>w.getVersion())
                throw ApiExceptions.conflict("DEFERRED_SOURCE: Current accepted-revision assignment is missing");
            return Optional.of(a);
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw ApiExceptions.conflict("DEFERRED_SOURCE: Assignment evidence is invalid");}
    }
    public void require(ActorPrincipal actor,Renewal n,RenewalOperationSources.Capability capability) {
        var f=n.getRental().getFacility().getId();
        if(capability==RenewalOperationSources.Capability.EXCEPTION||capability==RenewalOperationSources.Capability.REFUND) {
            if(capability==RenewalOperationSources.Capability.REFUND)access.require(actor,RoleCode.MANAGER,f,FacilityScopeLevel.MANAGE,SystemPermission.VIEW_RENTALS,SystemPermission.MANAGE_RENTALS,SystemPermission.VIEW_PAYMENTS,SystemPermission.MANAGE_PAYMENTS);
            else access.require(actor,RoleCode.MANAGER,f,FacilityScopeLevel.MANAGE,SystemPermission.VIEW_RENTALS,SystemPermission.MANAGE_RENTALS);
            return;
        }
        if(capability==RenewalOperationSources.Capability.CASH)access.require(actor,RoleCode.STAFF,f,FacilityScopeLevel.OPERATE,SystemPermission.VIEW_RENTALS,SystemPermission.VIEW_PAYMENTS,SystemPermission.MANAGE_PAYMENTS);
        else if(capability==RenewalOperationSources.Capability.READ)access.require(actor,RoleCode.STAFF,f,FacilityScopeLevel.OPERATE,SystemPermission.VIEW_RENTALS);
        else access.require(actor,RoleCode.STAFF,f,FacilityScopeLevel.OPERATE,SystemPermission.VIEW_RENTALS,SystemPermission.MANAGE_RENTALS);

    }
    public Set<UUID> assignedRenewalIds(ActorPrincipal actor) {
        access.require(actor,RoleCode.STAFF,null,FacilityScopeLevel.OPERATE,SystemPermission.VIEW_RENTALS);
        var scopes=actor.facilityScopes().entrySet().stream().filter(e->e.getValue()!=null&&e.getValue().includes(FacilityScopeLevel.OPERATE)&&access.scope(actor.userId(),e.getKey(),FacilityScopeLevel.OPERATE)).map(Map.Entry::getKey).toList();
        if(scopes.isEmpty())throw ApiExceptions.forbidden("Current Staff OPERATE scope required");
        // Method name retained for the AuthorizationSource compatibility contract.
        return Set.copyOf(em.createQuery("select n.id from Renewal n where n.rental.facility.id in :scopes",UUID.class)
            .setParameter("scopes",scopes).getResultList());
    }
    public void requireAssignable(User target,UUID facility) {
        if(!access.eligible(target,RoleCode.STAFF,SystemPermission.VIEW_RENTALS,SystemPermission.MANAGE_RENTALS)||!access.scope(target.getId(),facility,FacilityScopeLevel.OPERATE))throw ApiExceptions.validation("Staff must be active with Renewal permissions and OPERATE scope",null);
    }
}
