package com.storagehub.api.facility;

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
@RequestMapping("/api/manager")
@RequiredArgsConstructor
public class ManagerUnitTypeLifecycleController {
    private final ActorContext actors;
    private final FacilityLifecycleService lifecycle;

    @PostMapping("/facilities/{facilityId}/unit-types")
    public ApiResponse<UnitTypeResponse> create(
        @PathVariable UUID facilityId,
        @Valid @RequestBody ManagerUnitTypeRequest request
    ) {
        return new ApiResponse<>(lifecycle.createUnitType(actors.required(), facilityId, request), CorrelationIdContext.current());
    }

    @PatchMapping("/unit-types/{unitTypeId}")
    public ApiResponse<UnitTypeResponse> update(
        @PathVariable UUID unitTypeId,
        @Valid @RequestBody ManagerUnitTypeRequest request
    ) {
        return new ApiResponse<>(lifecycle.updateUnitType(actors.required(), unitTypeId, request), CorrelationIdContext.current());
    }
}
