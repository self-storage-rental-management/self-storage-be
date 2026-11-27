package com.storagehub.api.facility;

import com.storagehub.api.configuration.UnitTypePricingRequest;
import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.FacilityLifecycleService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/business")
@RequiredArgsConstructor
public class BusinessFacilityLifecycleController {
    private final ActorContext actors;
    private final FacilityLifecycleService lifecycle;

    @org.springframework.web.bind.annotation.DeleteMapping("/facilities/{facilityId}")
    public ApiResponse<FacilityResponse> delete(@PathVariable UUID facilityId) {
        // Compatibility route only. The operation is still a guarded soft
        // archive; no physical row is deleted.
        return new ApiResponse<>(lifecycle.archiveFacility(actors.required(), facilityId), CorrelationIdContext.current());
    }

    @PatchMapping("/facilities/{facilityId}")
    public ApiResponse<FacilityResponse> update(
        @PathVariable UUID facilityId,
        @Valid @RequestBody UpdateFacilityRequest request
    ) {
        return new ApiResponse<>(lifecycle.updateFacility(actors.required(), facilityId, request), CorrelationIdContext.current());
    }

    @PostMapping("/facilities/{facilityId}/archive")
    public ApiResponse<FacilityResponse> archive(@PathVariable UUID facilityId) {
        return new ApiResponse<>(lifecycle.archiveFacility(actors.required(), facilityId), CorrelationIdContext.current());
    }

    @PatchMapping("/facilities/{facilityId}/deadline-policies")
    public ApiResponse<java.util.Map<UUID, com.storagehub.api.configuration.UnitTypeDeadlinePolicy>> updateDeadlinePolicies(
        @PathVariable UUID facilityId,
        @Valid @RequestBody com.storagehub.api.configuration.FacilityDeadlinePoliciesRequest request) {
        return new ApiResponse<>(lifecycle.updateDeadlinePolicies(actors.required(), facilityId, request), CorrelationIdContext.current());
    }

    @GetMapping("/unit-types/{unitTypeId}/deadline-policy")
    public ApiResponse<com.storagehub.api.configuration.UnitTypeDeadlinePolicy> deadlinePolicy(@PathVariable UUID unitTypeId) {
        return new ApiResponse<>(lifecycle.deadlinePolicy(actors.required(), unitTypeId), CorrelationIdContext.current());
    }
    @PatchMapping("/unit-types/{unitTypeId}/deadline-policy")
    public ApiResponse<com.storagehub.api.configuration.UnitTypeDeadlinePolicy> updateDeadlinePolicy(@PathVariable UUID unitTypeId,
        @Valid @RequestBody com.storagehub.api.configuration.UnitTypeDeadlinePolicy request) {
        return new ApiResponse<>(lifecycle.updateDeadlinePolicy(actors.required(), unitTypeId, request), CorrelationIdContext.current());
    }

    @PatchMapping("/unit-types/{unitTypeId}/pricing")
    public ApiResponse<UnitTypeResponse> price(
        @PathVariable UUID unitTypeId,
        @Valid @RequestBody UnitTypePricingRequest request
    ) {
        return new ApiResponse<>(lifecycle.priceUnitType(actors.required(), unitTypeId, request), CorrelationIdContext.current());
    }

    @PostMapping("/facilities/{facilityId}/publish")
    public ApiResponse<FacilityResponse> publish(@PathVariable UUID facilityId) {
        return new ApiResponse<>(lifecycle.publish(actors.required(), facilityId), CorrelationIdContext.current());
    }

    @GetMapping("/facilities/{facilityId}/lifecycle-summary")
    public ApiResponse<FacilityLifecycleSummary> lifecycleSummary(@PathVariable UUID facilityId) {
        return new ApiResponse<>(lifecycle.getFacilityLifecycleSummaryForBusiness(actors.required(), facilityId), CorrelationIdContext.current());
    }

    @PostMapping("/facilities/{facilityId}/restore")
    public ApiResponse<FacilityResponse> restore(@PathVariable UUID facilityId) {
        return new ApiResponse<>(lifecycle.restoreFacility(actors.required(), facilityId), CorrelationIdContext.current());
    }

    @GetMapping("/facility-lifecycle-requests")
    public ApiResponse<java.util.List<FacilityLifecycleChangeResponse>> lifecycleRequests() {
        return new ApiResponse<>(lifecycle.listFacilityLifecycleChanges(actors.required()), CorrelationIdContext.current());
    }

    @PostMapping("/facility-lifecycle-requests/{requestId}/review")
    public ApiResponse<FacilityLifecycleChangeResponse> reviewLifecycleRequest(
        @PathVariable UUID requestId,
        @Valid @RequestBody FacilityLifecycleReviewRequest request
    ) {
        return new ApiResponse<>(lifecycle.reviewFacilityLifecycleChange(actors.required(), requestId, request), CorrelationIdContext.current());
    }
}
