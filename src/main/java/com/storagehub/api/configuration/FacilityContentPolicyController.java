package com.storagehub.api.configuration;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.FacilityContentPolicyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class FacilityContentPolicyController {
    private final ActorContext actors;
    private final FacilityContentPolicyService service;
    public record Save(@Min(-1) long version, @NotNull @Size(max=1000) List<@NotNull @Valid FacilityContentPolicy> policies) {}
    @GetMapping("/api/business/policies/content")
    public ApiResponse<FacilityContentPolicyService.Catalog> list() {
        return new ApiResponse<>(service.list(actors.required()), CorrelationIdContext.current());
    }
    @PutMapping("/api/business/policies/content")
    public ApiResponse<FacilityContentPolicyService.Catalog> save(@Valid @RequestBody Save body) {
        return new ApiResponse<>(service.save(actors.required(), body.version(), body.policies()), CorrelationIdContext.current());
    }
    @GetMapping("/api/customer/facilities/{facilityId}/content-policies")
    public ApiResponse<List<FacilityContentPolicy>> customer(@PathVariable UUID facilityId) {
        actors.required();
        return new ApiResponse<>(service.customerPolicies(facilityId), CorrelationIdContext.current());
    }
}
