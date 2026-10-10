package com.storagehub.api.facility;

import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.service.FacilityQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/facilities")
@RequiredArgsConstructor
public class PublicFacilityController {

    private final FacilityQueryService facilityQueryService;

    @GetMapping
    public PageResponse<PublicFacilityResponse> list(
        @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return facilityQueryService.searchPublicFacilities(pageable, CorrelationIdContext.current());
    }
}
