package com.storagehub.api.notification;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.WorkflowSlaScheduler;
import com.storagehub.service.WorkflowSlaMetrics;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflow-sla")
@RequiredArgsConstructor
public class WorkflowSlaController {
    private final ActorContext actors;
    private final WorkflowSlaScheduler workflow;
    private final WorkflowSlaMetrics metrics;

    public record Resolution(String note) {}
    public record DeadlineExtension(String workflow, Instant previousDueAt, Instant dueAt, String note) {}

    @GetMapping
    public ApiResponse<List<WorkflowSlaScheduler.WorkItem>> list() {
        return new ApiResponse<>(workflow.list(actors.required()), CorrelationIdContext.current());
    }

    @GetMapping("/metrics")
    public ApiResponse<List<WorkflowSlaMetrics.Row>> metrics() {
        return new ApiResponse<>(metrics.summary(actors.required()), CorrelationIdContext.current());
    }

    @PostMapping("/check-in-rejections/{id}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolve(@PathVariable UUID id, @RequestBody Resolution resolution) {
        workflow.resolveRejection(actors.required(), id, resolution.note());
    }

    @PostMapping("/inventory/{id}/extend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void extendInventory(@PathVariable UUID id, @RequestBody DeadlineExtension extension) {
        workflow.extendInventoryDeadline(actors.required(), id, extension.workflow(), extension.previousDueAt(),
            extension.dueAt(), extension.note());
    }
}
