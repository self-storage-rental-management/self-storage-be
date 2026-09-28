package com.storagehub.api.facility;

import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.UnitTypeStatus;
import com.storagehub.security.ActorContext;
import com.storagehub.service.UnitTypeQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/facilities/{facilityId}/unit-types")
@RequiredArgsConstructor
public class UnitTypeController {

    private final ActorContext actorContext;
    private final UnitTypeQueryService unitTypeQueryService;

    @GetMapping
    public PageResponse<UnitTypeResponse> list(
        @PathVariable UUID facilityId,
        @RequestParam(required = false) UnitTypeStatus status,
        @PageableDefault(size = 20, sort = "monthlyPrice", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return unitTypeQueryService.list(
            actorContext.required(), facilityId, status, pageable, CorrelationIdContext.current()
        );
    }
}
