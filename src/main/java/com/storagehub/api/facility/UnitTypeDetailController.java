package com.storagehub.api.facility;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.UnitTypeQueryService;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/unit-types")
@RequiredArgsConstructor
public class UnitTypeDetailController {

    private final ActorContext actorContext;
    private final UnitTypeQueryService unitTypeQueryService;

    @GetMapping("/{unitTypeId}")
    public ApiResponse<UnitTypeResponse> get(
        @PathVariable UUID unitTypeId,
        @RequestParam(required = false) LocalDate startDate,
        @RequestParam(required = false) LocalDate endDate
    ) {
        return new ApiResponse<>(
            unitTypeQueryService.get(actorContext.required(), unitTypeId, startDate, endDate),
            CorrelationIdContext.current()
        );
    }
}
