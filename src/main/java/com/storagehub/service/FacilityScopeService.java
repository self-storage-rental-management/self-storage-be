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
        if (actor == null || actor.hasRole(RoleCode.ADMIN)) return false;
        if (actor.hasRole(RoleCode.BUSINESS)) {
            return actor.facilityScopes() != null && !actor.facilityScopes().isEmpty();
        }
        return actor.hasAnyRole(RoleCode.STAFF, RoleCode.MANAGER);
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

    public boolean hasGlobalScope(ActorPrincipal actor) {
        if (actor == null) return false;
        if (actor.hasRole(RoleCode.ADMIN)) return true;
        if (actor.hasRole(RoleCode.BUSINESS)) {
            return actor.facilityScopes() == null || actor.facilityScopes().isEmpty();
        }
        return false;
    }

    public boolean canAccessFacility(ActorPrincipal actor, UUID facilityId, FacilityScopeLevel required) {
        if (actor == null) return false;
        if (actor.hasRole(RoleCode.ADMIN)) return true;
        if (actor.hasRole(RoleCode.BUSINESS)) {
            if (actor.facilityScopes() == null || actor.facilityScopes().isEmpty()) {
                return true;
            }
            FacilityScopeLevel actual = actor.facilityScopes().get(facilityId);
            return actual != null && actual.includes(required);
        }
        if (actor.hasRole(RoleCode.CUSTOMER)) {
            return required == FacilityScopeLevel.READ;
        }
        FacilityScopeLevel actual = actor.facilityScopes().get(facilityId);
        return actual != null && actual.includes(required);
    }

    private void assertCanAccess(ActorPrincipal actor, UUID facilityId, FacilityScopeLevel required) {
        if (!canAccessFacility(actor, facilityId, required)) {
            throw ApiExceptions.forbidden("The actor has no required scope for this facility");
        }
    }
}
