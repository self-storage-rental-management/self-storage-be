package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.security.ActorPrincipal;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FacilityScopeService {

    public boolean isFacilityScoped(ActorPrincipal actor) {
        return !actor.hasAnyRole(RoleCode.ADMIN, RoleCode.BUSINESS)
            && actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER);
    }

    public void assertCanRead(ActorPrincipal actor, UUID facilityId) {
        assertCanAccess(actor, facilityId, FacilityScopeLevel.READ);
    }

    public void assertCanOperate(ActorPrincipal actor, UUID facilityId) {
        assertCanAccess(actor, facilityId, FacilityScopeLevel.OPERATE);
    }

    public void assertCanManage(ActorPrincipal actor, UUID facilityId) {
        assertCanAccess(actor, facilityId, FacilityScopeLevel.MANAGE);
    }

    private void assertCanAccess(ActorPrincipal actor, UUID facilityId, FacilityScopeLevel required) {
        if (actor.hasAnyRole(RoleCode.ADMIN, RoleCode.BUSINESS)) {
            return;
        }
        if (actor.hasRole(RoleCode.CUSTOMER)) {
            if (required == FacilityScopeLevel.READ) {
                return;
            }
            throw ApiExceptions.forbidden("The actor has no required scope for this facility");
        }

        FacilityScopeLevel actual = actor.facilityScopes().get(facilityId);
        if (actual == null || !actual.includes(required)) {
            throw ApiExceptions.forbidden("The actor has no required scope for this facility");
        }
    }
}
