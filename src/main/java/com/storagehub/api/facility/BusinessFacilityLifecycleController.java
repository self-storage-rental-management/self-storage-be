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
    public void delete(@PathVariable UUID facilityId) {
        lifecycle.deleteFacility(actors.required(), facilityId);
    }

    @PatchMapping("/facilities/{facilityId}")
    public ApiResponse<FacilityResponse> update(
        @PathVariable UUID facilityId,
        @Valid @RequestBody UpdateFacilityRequest request
    ) {
        return new ApiResponse<>(lifecycle.updateFacility(actors.required(), facilityId, request), CorrelationIdContext.current());
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
}
