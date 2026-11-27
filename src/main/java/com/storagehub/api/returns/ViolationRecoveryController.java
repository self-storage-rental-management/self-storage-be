package com.storagehub.api.returns;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.ViolationRecoveryWorkflow;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/violation-recoveries")
@RequiredArgsConstructor
public class ViolationRecoveryController {
    private final ActorContext actors;
    private final ViolationRecoveryWorkflow workflow;

    @GetMapping
    public ApiResponse<List<ViolationRecoveryWorkflow.Entry>> list(@RequestParam(defaultValue = "0") int page,
        @RequestParam(required = false) UUID sourceReturnId, @RequestParam(defaultValue = "false") boolean activeOnly,
        @RequestParam(defaultValue = "false") boolean escalatedOnly,
        @RequestParam(defaultValue = "priority") String sort) {
        var actor = actors.required();
        var entries = escalatedOnly ? workflow.listEscalatedOperations(actor, page)
            : sourceReturnId != null ? workflow.listForReturn(actor, sourceReturnId)
            : activeOnly ? workflow.listActiveOperations(actor, page) : workflow.list(actor, page, sort);
        return new ApiResponse<>(entries, CorrelationIdContext.current());
    }

    @GetMapping("/{id}/staff")
    public ApiResponse<List<ViolationRecoveryWorkflow.StaffOption>> staff(@PathVariable UUID id,
        @RequestParam(defaultValue = "false") boolean pickup, @RequestParam(defaultValue = "false") boolean witness) {
        return new ApiResponse<>(workflow.staff(actors.required(), id, pickup, witness), CorrelationIdContext.current());
    }

    @GetMapping("/customer")
    public com.storagehub.common.api.PageResponse<ViolationRecoveryWorkflow.Entry> customerPage(
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "8") int pageSize) {
        return workflow.customerPage(actors.required(), page, pageSize, CorrelationIdContext.current());
    }

    @GetMapping("/{id}")
    public ApiResponse<ViolationRecoveryWorkflow.Entry> get(@PathVariable UUID id) {
        return new ApiResponse<>(workflow.get(actors.required(), id), CorrelationIdContext.current());
    }

    @GetMapping("/{id}/preparation")
    public ApiResponse<com.storagehub.service.RecoveryReviewService.Preparation> preparation(@PathVariable UUID id) {
        return new ApiResponse<>(workflow.preparation(actors.required(), id), CorrelationIdContext.current());
    }

    @PostMapping("/{id}/actions")
    public ApiResponse<ViolationRecoveryWorkflow.Entry> execute(@PathVariable UUID id,
        @Valid @RequestBody ViolationRecoveryWorkflow.Command command) {
        return new ApiResponse<>(workflow.execute(actors.required(), id, command), CorrelationIdContext.current());
    }
}
