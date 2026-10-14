package com.storagehub.api.overdue;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.overdue.RecoveryStateReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/staff/rentals/{rentalId}/recovery-state")
@SecurityRequirement(name="bearerAuth") @Tag(name="D4 - Recovery integration")
public class StaffRecoveryStateController {
    private final ActorContext actors;
    private final RecoveryStateReadService states;
    @GetMapping @Operation(summary="Read receiver-owned Recovery state",
        description="Current rentals:read and READ facility scope required. UNKNOWN/null is not clear Recovery. LOCAL_DEMO and INTERNAL_TASK do not certify external Recovery coverage. Read-only; no receiver receipt is fabricated.")
    public ApiResponse<RecoveryStateReadService.View> read(@PathVariable UUID rentalId) {
        return new ApiResponse<>(states.read(actors.required(),rentalId),CorrelationIdContext.current());
    }
}
