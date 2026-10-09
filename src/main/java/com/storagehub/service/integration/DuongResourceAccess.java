package com.storagehub.service.integration;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.*;
import com.storagehub.security.ActorPrincipal;
import jakarta.persistence.EntityManager;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Current DB grants for new integration paths only; never changes shared role policies. */
@Component @RequiredArgsConstructor
public class DuongResourceAccess {
    private final EntityManager em;
    public User require(ActorPrincipal actor, RoleCode role, UUID facility, FacilityScopeLevel level, SystemPermission... permissions) {
        if(actor==null)throw ApiExceptions.unauthorized("Authentication required");
        var user=em.find(User.class,actor.userId());
        if(!actor.hasRole(role)||!eligible(user,role,permissions))throw ApiExceptions.forbidden("Current role/permissions required");
        for(var p:permissions)if(!actor.permissions().contains(p.code()))throw ApiExceptions.forbidden("Current permission required");
        if(facility!=null&&role!=RoleCode.BUSINESS) {
            var claimed=actor.facilityScopes().get(facility);
            if(claimed==null||!claimed.includes(level)||!scope(user.getId(),facility,level))throw ApiExceptions.notFound("Resource not found");
        }
        return user;
    }
    public boolean eligible(User user, RoleCode role, SystemPermission... permissions) {
        if(user==null||user.getStatus()!=UserStatus.ACTIVE||user.getRoles().stream().noneMatch(r->r.getCode()==role))return false;
        var grants=new HashSet<String>();user.getRoles().forEach(r->r.getEffectivePermissions().forEach(p->grants.add(p.getCode())));
        return Arrays.stream(permissions).allMatch(p->grants.contains(p.code()));
    }
    public boolean scope(UUID user,UUID facility,FacilityScopeLevel level) {
        return em.createQuery("select s.scopeLevel from UserFacilityScope s where s.user.id=:u and s.facility.id=:f",FacilityScopeLevel.class)
            .setParameter("u",user).setParameter("f",facility).setMaxResults(2).getResultList().stream().anyMatch(s->s.includes(level));
    }
}
