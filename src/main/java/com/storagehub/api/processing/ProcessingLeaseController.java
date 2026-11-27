package com.storagehub.api.processing;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.ProcessingLeaseService;
import com.storagehub.service.ProcessingLeaseService.Target;
import com.storagehub.service.ProcessingLeaseService.View;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/processing-locks/{type}/{id}")
@RequiredArgsConstructor
public class ProcessingLeaseController {
    public record TokenRequest(@NotNull UUID token, boolean reclaimSameOwner) {
        public TokenRequest(UUID token) { this(token, false); }
    }
    private final ActorContext actors;
    private final ProcessingLeaseService leases;
    @GetMapping public ApiResponse<View> status(@PathVariable Target type, @PathVariable UUID id,
        @RequestHeader(value = "X-Processing-Token", required = false) UUID token) {
        return envelope(leases.status(actors.required(), type, id, token));
    }
    @PostMapping("/acquire") public ApiResponse<View> acquire(@PathVariable Target type, @PathVariable UUID id,
        @Valid @RequestBody TokenRequest request) {
        return envelope(leases.acquire(actors.required(), type, id, request.token(), request.reclaimSameOwner()));
    }
    @PostMapping("/heartbeat") public ApiResponse<View> heartbeat(@PathVariable Target type, @PathVariable UUID id,
        @Valid @RequestBody TokenRequest request) {
        return envelope(leases.heartbeat(actors.required(), type, id, request.token()));
    }
    @PostMapping("/release") public ApiResponse<View> release(@PathVariable Target type, @PathVariable UUID id,
        @Valid @RequestBody TokenRequest request) {
        return envelope(leases.release(actors.required(), type, id, request.token()));
    }
    @PostMapping("/force-release") public ApiResponse<View> forceRelease(@PathVariable Target type, @PathVariable UUID id) {
        return envelope(leases.forceRelease(actors.required(), type, id));
    }
    private ApiResponse<View> envelope(View view) { return new ApiResponse<>(view, CorrelationIdContext.current()); }
}
