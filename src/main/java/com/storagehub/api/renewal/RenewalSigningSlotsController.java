package com.storagehub.api.renewal;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.calendar.RenewalSigningSlotsReadService;
import com.storagehub.service.renewal.operations.RenewalOperationService.Audience;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @SecurityRequirement(name="bearerAuth")
@Tag(name="D3 - Renewal signing calendar")
public class RenewalSigningSlotsController {
    private final ActorContext actors;
    private final RenewalSigningSlotsReadService slots;

    @GetMapping("/api/customer/renewals/{id}/signing-slots")
    @Operation(summary="Read available signing starts for my Renewal",description="Nonbinding current calendar candidates, not proof of financial/Recovery readiness. POST appointment rechecks version, assignment, deadline and capacity under locks.")
    public ApiResponse<RenewalSigningSlotsReadService.View> customer(@PathVariable UUID id,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date) {
        return response(slots.read(actors.required(),id,date,Audience.CUSTOMER));
    }
    @GetMapping("/api/staff/renewals/{id}/signing-slots")
    @Operation(summary="Read signing starts for a currently assigned Renewal")
    public ApiResponse<RenewalSigningSlotsReadService.View> staff(@PathVariable UUID id,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date) {
        return response(slots.read(actors.required(),id,date,Audience.STAFF));
    }
    private ApiResponse<RenewalSigningSlotsReadService.View> response(RenewalSigningSlotsReadService.View value) {
        return new ApiResponse<>(value,CorrelationIdContext.current());
    }
}
