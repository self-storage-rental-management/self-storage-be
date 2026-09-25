package com.storagehub.security;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.FacilityScopeLevel;
import com.storagehub.domain.model.RoleCode;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class ActorContext {

    public ActorPrincipal required() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw ApiExceptions.unauthorized("A valid JWT is required");
        }
        Jwt jwt = jwtAuthentication.getToken();
        try {
            return new ActorPrincipal(
                UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString("sid")),
                readRoles(jwt.getClaim("roles")),
                readScopes(jwt.getClaim("facilityScopes"))
            );
        } catch (IllegalArgumentException exception) {
            throw ApiExceptions.unauthorized("The JWT actor claims are invalid");
        }
    }

    private Set<RoleCode> readRoles(Object rawRoles) {
        Set<RoleCode> roles = EnumSet.noneOf(RoleCode.class);
        if (rawRoles instanceof Collection<?> values) {
            values.forEach(value -> roles.add(RoleCode.valueOf(String.valueOf(value))));
        }
        return Set.copyOf(roles);
    }

    private Map<UUID, FacilityScopeLevel> readScopes(Object rawScopes) {
        Map<UUID, FacilityScopeLevel> scopes = new LinkedHashMap<>();
        if (rawScopes instanceof Collection<?> values) {
            for (Object value : values) {
                String[] parts = String.valueOf(value).split(":", 2);
                if (parts.length == 2) {
                    scopes.put(UUID.fromString(parts[0]), FacilityScopeLevel.valueOf(parts[1]));
                }
            }
        }
        return Map.copyOf(scopes);
    }
}
