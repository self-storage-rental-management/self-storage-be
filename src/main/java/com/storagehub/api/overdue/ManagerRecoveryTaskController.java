package com.storagehub.api.overdue;

import com.storagehub.common.api.*;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.security.ActorContext;
import com.storagehub.service.overdue.RecoveryTaskReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/manager/recovery-tasks") @RequiredArgsConstructor
@ConditionalOnProperty(name="storagehub.integration.recovery-tasks.enabled",havingValue="true")
@SecurityRequirement(name="bearerAuth") @Tag(name="D4 - Recovery tasks")
public class ManagerRecoveryTaskController {
    private final ActorContext actors;
    private final RecoveryTaskReadService tasks;
    @GetMapping @Operation(summary="List persisted Recovery work items for a managed facility")
    public PageResponse<RecoveryTaskReadService.View> list(@RequestParam UUID facilityId,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return tasks.list(actors.required(),RoleCode.MANAGER,facilityId,page,size,CorrelationIdContext.current());
    }
    @GetMapping("/{id}") @Operation(summary="Read a persisted Recovery work item")
    public ApiResponse<RecoveryTaskReadService.View> detail(@PathVariable UUID id) {
        return new ApiResponse<>(tasks.detail(actors.required(),RoleCode.MANAGER,id),CorrelationIdContext.current());
    }
}
