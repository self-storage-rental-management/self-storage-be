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
    public ApiResponse<List<ViolationRecoveryWorkflow.Entry>> list(@RequestParam(defaultValue = "0") int page) {
        return new ApiResponse<>(workflow.list(actors.required(), page), CorrelationIdContext.current());
    }

    @GetMapping("/{id}/staff")
    public ApiResponse<List<ViolationRecoveryWorkflow.StaffOption>> staff(@PathVariable UUID id) {
        return new ApiResponse<>(workflow.staff(actors.required(), id), CorrelationIdContext.current());
    }

    @PostMapping("/{id}/actions")
    public ApiResponse<ViolationRecoveryWorkflow.Entry> execute(@PathVariable UUID id,
        @Valid @RequestBody ViolationRecoveryWorkflow.Command command) {
        return new ApiResponse<>(workflow.execute(actors.required(), id, command), CorrelationIdContext.current());
    }
}
