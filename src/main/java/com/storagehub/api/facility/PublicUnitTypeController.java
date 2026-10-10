package com.storagehub.api.facility;

import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.service.UnitTypeQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/unit-types")
@RequiredArgsConstructor
public class PublicUnitTypeController {

    private final UnitTypeQueryService unitTypeQueryService;

    @GetMapping
    public PageResponse<PublicUnitTypeResponse> list(
        @PageableDefault(size = 50, sort = "monthlyPrice", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return unitTypeQueryService.searchPublicUnitTypes(pageable, CorrelationIdContext.current());
    }
}
