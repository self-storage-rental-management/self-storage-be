package com.storagehub.service.overdue;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import com.storagehub.service.integration.DuongResourceAccess;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class RecoveryStateReadService {
    private final EntityManager em;
    private final DuongResourceAccess access;
    private final ObjectProvider<OverdueSources.RecoverySource> recoveries;
    private final ObjectProvider<Clock> clocks;
    public record View(UUID rentalId,UUID facilityId,Instant checkedAt,String sourceCoverage,Boolean recoveryBlocked) {}

    public View read(ActorPrincipal actor,UUID rentalId) {
        access.require(actor,RoleCode.STAFF,null,FacilityScopeLevel.READ,SystemPermission.VIEW_RENTALS);
        var rental=em.find(Rental.class,rentalId);
        if(rental==null||rental.getFacility()==null)throw ApiExceptions.notFound("Rental not found");
        UUID facility=rental.getFacility().getId();
        access.require(actor,RoleCode.STAFF,facility,FacilityScopeLevel.READ,SystemPermission.VIEW_RENTALS);
        var clock=clocks.getIfAvailable();Instant now=(clock==null?Clock.systemUTC():clock).instant();
        var source=recoveries.getIfAvailable();
        if(source==null)return new View(rentalId,facility,now,"UNKNOWN",null);
        var state=source.state(rental,now);
        if(state.isEmpty())return new View(rentalId,facility,now,"UNKNOWN",null);
        var evidence=state.get();
        if(!Objects.equals(evidence.rentalId(),rentalId)||!Objects.equals(evidence.facilityId(),facility)||!Objects.equals(evidence.checkedAt(),now))
            throw ApiExceptions.conflict("DEFERRED_SOURCE: Recovery state is stale or belongs to another rental");
        boolean demo=LocalDemoRecoverySource.class.isAssignableFrom(org.springframework.aop.support.AopUtils.getTargetClass(source));
        return new View(rentalId,facility,now,demo?"LOCAL_DEMO":"VERIFIED",evidence.recoveryBlocked());
    }
}
