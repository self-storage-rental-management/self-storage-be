package com.storagehub.service.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagehub.domain.model.*;
import com.storagehub.service.integration.DuongResourceAccess;
import com.storagehub.service.renewal.operations.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** A missing or ambiguous fault decision never authorizes forfeiture. No effect on physical Recovery. */
@Component @RequiredArgsConstructor
public class RenewalDepositFaultGuard {
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final DuongResourceAccess access;
    public String holdReason(Renewal renewal,RenewalOperationState state) {
        if(state.getPendingExceptionRef()!=null)return "AWAITING_FAULT_REVIEW";
        var incidents=em.createQuery("select e from RenewalOperationEvent e where e.renewal.id=:id and e.kind='INCIDENT'",RenewalOperationEvent.class)
            .setParameter("id",renewal.getId()).getResultList();
        var reviews=em.createQuery("select e from RenewalOperationEvent e where e.renewal.id=:id and e.kind='FAULT_REVIEW' order by e.occurredAt desc,e.id desc",RenewalOperationEvent.class)
            .setParameter("id",renewal.getId()).getResultList();
        for(var incident:incidents) {
            RenewalOperationEvent latest=null;
            try {
                for(var review:reviews) {
                    var data=mapper.readTree(review.getPayloadJson());
                    if(!incident.getId().toString().equals(data.path("incidentId").asText()))continue;
                    if(latest!=null) {
                        if(latest.getOccurredAt().equals(review.getOccurredAt()))return "AWAITING_FAULT_REVIEW";
                        break;
                    }
                    latest=review;
                }
                if(latest==null)return "AWAITING_FAULT_REVIEW";
                var data=mapper.readTree(latest.getPayloadJson());var reviewer=latest.getActorId()==null?null:em.find(User.class,latest.getActorId());
                if(!data.path("facilityFault").isBoolean()||latest.getOccurredAt().isBefore(incident.getOccurredAt())
                    ||!access.eligible(reviewer,RoleCode.MANAGER,SystemPermission.VIEW_RENTALS,SystemPermission.MANAGE_RENTALS)
                    ||!access.scope(latest.getActorId(),renewal.getRental().getFacility().getId(),FacilityScopeLevel.MANAGE))return "AWAITING_FAULT_REVIEW";
                if(data.path("facilityFault").booleanValue())return "FACILITY_FAULT";
            } catch(Exception invalid) {return "AWAITING_FAULT_REVIEW";}
        }
        return null;
    }
}
