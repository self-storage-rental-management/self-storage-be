package com.storagehub.api.renewal;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.renewal.RenewalRiskReadService;
import com.storagehub.service.renewal.operations.RenewalOperationService.Audience;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class RenewalRiskController {
    private final ActorContext actors;
    private final RenewalRiskReadService service;
    @GetMapping("/api/{role:customer|manager}/rentals/{id}/renewal-risk")
    public ApiResponse<RenewalRiskReadService.View> rental(@PathVariable String role,@PathVariable UUID id) {
        return new ApiResponse<>(service.rental(actors.required(),id,role.equals("manager")),CorrelationIdContext.current());
    }
    @GetMapping("/api/{role:customer|manager|staff}/renewals/{id}/renewal-risk")
    public ApiResponse<RenewalRiskReadService.View> renewal(@PathVariable String role,@PathVariable UUID id) {
        return new ApiResponse<>(service.renewal(actors.required(),id,Audience.valueOf(role.toUpperCase(java.util.Locale.ROOT))),CorrelationIdContext.current());
    }
}
