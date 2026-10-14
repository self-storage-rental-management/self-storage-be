package com.storagehub.api.overdue;

import com.storagehub.common.api.*;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.security.ActorContext;
import com.storagehub.service.FacilityScopeService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.recovery-tasks.enabled", havingValue="false", matchIfMissing=true)
public class DisabledRecoveryTaskController {
    private final ActorContext actors;
    private final FacilityScopeService scopes;

    @GetMapping({"/api/manager/recovery-tasks", "/api/staff/recovery-tasks"})
    public void list(@RequestParam UUID facilityId) {
        var actor = actors.required();
        if (!actor.hasAnyRole(RoleCode.MANAGER, RoleCode.STAFF)) throw ApiExceptions.forbidden("Facility staff role is required");
        scopes.assertCanRead(actor, facilityId);
        throw new ApiException(ErrorCode.FEATURE_DISABLED, HttpStatus.SERVICE_UNAVAILABLE, "Recovery tasks are not enabled");
    }
}
