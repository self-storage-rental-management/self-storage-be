package com.storagehub.api.facility;

import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.FacilityStatus;
import com.storagehub.security.ActorContext;
import com.storagehub.service.FacilityQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.storagehub.common.api.ApiResponse;
import java.util.UUID;

@RestController
@RequestMapping("/api/facilities")
@RequiredArgsConstructor
public class FacilityController {

    private final ActorContext actorContext;
    private final FacilityQueryService facilityQueryService;

    @GetMapping
    public PageResponse<FacilityResponse> list(
        @RequestParam(required = false) FacilityStatus status,
        @RequestParam(required = false) String city,
        @RequestParam(name = "q", required = false) String query,
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return facilityQueryService.searchFacilities(
            actorContext.required(), status, city, query, pageable, CorrelationIdContext.current()
        );
    }

    @GetMapping("/{facilityId}")
    public ApiResponse<FacilityResponse> get(@PathVariable UUID facilityId) {
        return new ApiResponse<>(
            facilityQueryService.getFacility(actorContext.required(), facilityId),
            CorrelationIdContext.current()
        );
    }
}
