package com.storagehub.api.configuration;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.BusinessPolicyService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/business")
@RequiredArgsConstructor
public class BusinessPolicyController {

    private final ActorContext actorContext;
    private final BusinessPolicyService businessPolicyService;

    @GetMapping("/config")
    public ApiResponse<BusinessConfigResponse> getBusinessConfig() {
        return new ApiResponse<>(
            businessPolicyService.getBusinessConfig(actorContext.required()),
            CorrelationIdContext.current()
        );
    }

    @PutMapping("/config")
    public ApiResponse<BusinessConfigResponse> updateBusinessConfig(
        @Valid @RequestBody UpdateBusinessConfigRequest request
    ) {
        return new ApiResponse<>(
            businessPolicyService.updateBusinessConfig(actorContext.required(), request),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/policies/packages")
    public ApiResponse<List<RentalPackagePolicyResponse>> listPackagePolicies(
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(required = false) Boolean activeOnly
    ) {
        return new ApiResponse<>(
            businessPolicyService.listPackagePolicies(actorContext.required(), facilityId, activeOnly),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/policies/packages/{id}")
    public ApiResponse<RentalPackagePolicyResponse> getPackagePolicy(@PathVariable UUID id) {
        return new ApiResponse<>(
            businessPolicyService.getPackagePolicy(actorContext.required(), id),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/policies/packages")
    public ApiResponse<RentalPackagePolicyResponse> createPackagePolicy(
        @Valid @RequestBody RentalPackagePolicyRequest request
    ) {
        return new ApiResponse<>(
            businessPolicyService.createPackagePolicy(actorContext.required(), request),
            CorrelationIdContext.current()
        );
    }

    @PutMapping("/policies/packages/{id}")
    public ApiResponse<RentalPackagePolicyResponse> updatePackagePolicy(
        @PathVariable UUID id,
        @Valid @RequestBody RentalPackagePolicyRequest request
    ) {
        return new ApiResponse<>(
            businessPolicyService.updatePackagePolicy(actorContext.required(), id, request),
            CorrelationIdContext.current()
        );
    }

    @DeleteMapping("/policies/packages/{id}")
    public ApiResponse<Void> deletePackagePolicy(@PathVariable UUID id) {
        businessPolicyService.deletePackagePolicy(actorContext.required(), id);
        return new ApiResponse<>(null, CorrelationIdContext.current());
    }
}
