package com.storagehub.service;

import com.storagehub.common.api.ApiExceptions;
import com.storagehub.domain.model.SystemPermission;
import com.storagehub.security.ActorPrincipal;
import org.springframework.stereotype.Service;

@Service
public class AdminAuthorizationService {

    public void require(ActorPrincipal actor, SystemPermission permission) {
        if (!actor.hasPermission(permission)) {
            throw ApiExceptions.forbidden("The JWT actor does not have permission: " + permission.code());
        }
    }

}
