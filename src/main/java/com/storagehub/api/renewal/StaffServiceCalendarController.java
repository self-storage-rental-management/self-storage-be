package com.storagehub.api.renewal;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.calendar.ServiceCalendarReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/staff/facilities/{facilityId}/service-calendar-policy")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "D3 - Staff Renewal Operations")
public class StaffServiceCalendarController {
    private final ActorContext actors;
    private final ServiceCalendarReadService calendars;

    @GetMapping
    @Operation(summary = "Read team service hours for a scoped facility",
        description = "Requires current rentals:read and READ facility scope. Policy only: NOT_EVALUATED availability is not a reservable slot or verified Renewal eligibility.")
    public ApiResponse<ServiceCalendarPolicyResponse> read(@PathVariable UUID facilityId) {
        return new ApiResponse<>(calendars.read(actors.required(), facilityId), CorrelationIdContext.current());
    }
}
