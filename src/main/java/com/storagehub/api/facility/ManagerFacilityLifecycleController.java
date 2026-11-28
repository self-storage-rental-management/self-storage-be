package com.storagehub.api.facility;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.FacilityLifecycleService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager")
@RequiredArgsConstructor
public class ManagerFacilityLifecycleController {
    private final ActorContext actors;
    private final FacilityLifecycleService lifecycle;

    @GetMapping("/facilities/{facilityId}/lifecycle-summary")
    public ApiResponse<FacilityLifecycleSummary> lifecycleSummary(@PathVariable UUID facilityId) {
        return new ApiResponse<>(lifecycle.getFacilityLifecycleSummaryForManager(actors.required(), facilityId), CorrelationIdContext.current());
    }

    @PostMapping("/facilities/{facilityId}/lifecycle-requests")
    public ApiResponse<FacilityLifecycleChangeResponse> request(
        @PathVariable UUID facilityId,
        @Valid @RequestBody FacilityLifecycleRequestBody body
    ) {
        return new ApiResponse<>(lifecycle.requestFacilityLifecycleChange(actors.required(), facilityId, body), CorrelationIdContext.current());
    }
}
