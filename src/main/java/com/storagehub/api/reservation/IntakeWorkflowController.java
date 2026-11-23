package com.storagehub.api.reservation;
import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.IntakeWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/intake-workflow/reservations")
public class IntakeWorkflowController {
    private final IntakeWorkflowService service;
    private final ActorContext actors;
    @PostMapping("/returns/{id}/arrival")
    public ApiResponse<Void> returnArrival(@PathVariable UUID id) {
        service.readyForReturn(actors.required(), id);
        return new ApiResponse<>(null, CorrelationIdContext.current());
    }
    @GetMapping("/{id}")
    public ApiResponse<Map<String,Object>> read(@PathVariable UUID id) {
        return new ApiResponse<>(service.read(actors.required(), id), CorrelationIdContext.current());
    }
    @PostMapping("/{id}")
    public ApiResponse<Map<String,Object>> change(@PathVariable UUID id, @RequestBody IntakeWorkflowService.Command command) {
        return new ApiResponse<>(service.change(actors.required(), id, command), CorrelationIdContext.current());
    }
}
