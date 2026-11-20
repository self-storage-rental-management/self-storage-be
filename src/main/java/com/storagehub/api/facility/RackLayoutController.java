package com.storagehub.api.facility;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.security.ActorContext;
import com.storagehub.service.RackLayoutService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RackLayoutController {
    private final ActorContext actors;
    private final RackLayoutService layouts;

    @GetMapping("/manager/unit-types/{unitTypeId}/rack-layout")
    public ApiResponse<RackLayout.Saved> managerLayout(@PathVariable UUID unitTypeId) {
        return new ApiResponse<>(layouts.readForManager(actors.required(), unitTypeId), CorrelationIdContext.current());
    }

    @PostMapping("/manager/facilities/{facilityId}/unit-types/rack-layout/preview")
    public ApiResponse<RackLayout.Preview> previewDraft(@PathVariable UUID facilityId,
                                                       @Valid @RequestBody RackLayout.Draft request) {
        return new ApiResponse<>(layouts.previewDraft(actors.required(), facilityId, request), CorrelationIdContext.current());
    }

    @PostMapping("/manager/unit-types/{unitTypeId}/rack-layout/preview")
    public ApiResponse<RackLayout.Preview> preview(@PathVariable UUID unitTypeId,
                                                   @Valid @RequestBody RackLayout.Configuration request) {
        return new ApiResponse<>(layouts.preview(actors.required(), unitTypeId, request), CorrelationIdContext.current());
    }

    @PostMapping("/manager/unit-types/{unitTypeId}/rack-layout/preview-draft")
    public ApiResponse<RackLayout.Preview> previewDraftForUnitType(@PathVariable UUID unitTypeId,
                                                                   @Valid @RequestBody RackLayout.Draft request) {
        return new ApiResponse<>(layouts.previewDraftForUnitType(actors.required(), unitTypeId, request), CorrelationIdContext.current());
    }

    @PutMapping("/manager/unit-types/{unitTypeId}/rack-layout")
    public ApiResponse<RackLayout.Saved> save(@PathVariable UUID unitTypeId, @Valid @RequestBody RackLayout.Save request) {
        return new ApiResponse<>(layouts.save(actors.required(), unitTypeId, request), CorrelationIdContext.current());
    }

    @GetMapping("/business/rack-layouts")
    public PageResponse<RackLayout.TypeSummary> businessLayouts(@RequestParam(defaultValue = "0") int page) {
        return PageResponse.from(layouts.listForBusiness(actors.required(), PageRequest.of(Math.max(0, page), 20)),
            CorrelationIdContext.current());
    }

    @GetMapping("/business/rack-layouts/{unitTypeId}")
    public ApiResponse<RackLayout.Saved> businessLayout(@PathVariable UUID unitTypeId) {
        return new ApiResponse<>(layouts.readForBusiness(actors.required(), unitTypeId), CorrelationIdContext.current());
    }

    @GetMapping("/customer/facilities/{facilityId}/unit-types/{unitTypeId}/rack-layout")
    public ApiResponse<RackLayout.Result> customerLayout(@PathVariable UUID facilityId, @PathVariable UUID unitTypeId) {
        return new ApiResponse<>(layouts.readForCustomer(actors.required(), facilityId, unitTypeId), CorrelationIdContext.current());
    }
}
