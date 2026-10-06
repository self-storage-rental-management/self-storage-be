package com.storagehub.api.facility;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.AvailabilityQueryService;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/availability")
@RequiredArgsConstructor
public class AvailabilityController {

    private final ActorContext actorContext;
    private final AvailabilityQueryService availabilityQueryService;

    @GetMapping
    public ApiResponse<AvailabilityResponse> get(
        @RequestParam UUID facilityId,
        @RequestParam UUID unitTypeId,
        @RequestParam LocalDate startDate,
        @RequestParam LocalDate endDate
    ) {
        return new ApiResponse<>(
            availabilityQueryService.get(
                actorContext.required(), facilityId, unitTypeId, startDate, endDate
            ),
            CorrelationIdContext.current()
        );
    }
}
