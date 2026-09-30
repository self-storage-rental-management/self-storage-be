package com.storagehub.api.facility;

import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.security.ActorContext;
import com.storagehub.service.FacilityQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/storage-units")
@RequiredArgsConstructor
public class StorageUnitController {

    private final ActorContext actorContext;
    private final FacilityQueryService facilityQueryService;

    @GetMapping
    public PageResponse<StorageUnitResponse> list(
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(required = false) StorageUnitStatus status,
        @RequestParam(required = false) UUID unitTypeId,
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return facilityQueryService.searchUnits(
            actorContext.required(), facilityId, status, unitTypeId, pageable, CorrelationIdContext.current()
        );
    }
}
