package com.storagehub.api.overdue;

import com.storagehub.common.api.*;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.security.ActorContext;
import com.storagehub.service.overdue.RecoveryTaskOperationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/{role:staff|manager}/recovery-tasks/{id}")
@ConditionalOnProperty(name="storagehub.integration.recovery-tasks.enabled",havingValue="true")
@SecurityRequirement(name="bearerAuth") @Tag(name="D4 - Recovery task operations")
public class RecoveryTaskOperationController {
    private final ActorContext actors;
    private final RecoveryTaskOperationService service;
    @GetMapping("/operations") @Operation(summary="Read Recovery progress, version, linked settlement and evidence")
    public ApiResponse<RecoveryTaskOperationService.View> read(@PathVariable String role,@PathVariable UUID id){return response(service.read(actors.required(),role(role),id));}
    @PostMapping("/start") @Operation(summary="Start physical Recovery work; does not release the unit")
    public ApiResponse<RecoveryTaskOperationService.View> start(@PathVariable String role,@PathVariable UUID id,@Valid @RequestBody RecoveryTaskCommands.Start body,@RequestHeader("Idempotency-Key") String key){return response(service.start(actors.required(),role(role),id,body,key));}
    @PostMapping("/inspection") @Operation(summary="Record Recovery inventory and create the linked Return settlement",description="Evidence files must be uploaded for this Recovery task. Original Rental security deposit remains separate from forfeited Renewal deposit.")
    public ApiResponse<RecoveryTaskOperationService.View> inspection(@PathVariable String role,@PathVariable UUID id,@Valid @RequestBody RecoveryTaskCommands.Inspection body,@RequestHeader("Idempotency-Key") String key){return response(service.inspect(actors.required(),role(role),id,body,key));}
    @PostMapping("/settlement-receipts") @Operation(summary="Attest actual collection/refund against the linked Return settlement",description="Requires payments:collect, transaction reference and bound receipt file. Server determines direction and amount; no simulated gateway payment.")
    public ApiResponse<RecoveryTaskOperationService.View> settlement(@PathVariable String role,@PathVariable UUID id,@Valid @RequestBody RecoveryTaskCommands.Settlement body,@RequestHeader("Idempotency-Key") String key){return response(service.settle(actors.required(),role(role),id,body,key));}
    @PostMapping("/completion") @Operation(summary="Close Recovery and release the inspected unit after settlement",description="Requires completed settlement, explicit physical clearance/unit inspection and a bound completion report. Commits Rental completion, task closure and unit available/maintenance atomically.")
    public ApiResponse<RecoveryTaskOperationService.View> completion(@PathVariable String role,@PathVariable UUID id,@Valid @RequestBody RecoveryTaskCommands.Completion body,@RequestHeader("Idempotency-Key") String key){return response(service.complete(actors.required(),role(role),id,body,key));}
    private static RoleCode role(String value){return RoleCode.valueOf(value.toUpperCase(Locale.ROOT));}
    private static ApiResponse<RecoveryTaskOperationService.View> response(RecoveryTaskOperationService.View data){return new ApiResponse<>(data,CorrelationIdContext.current());}
}
