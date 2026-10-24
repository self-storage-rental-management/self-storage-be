package com.storagehub.api.facility;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.FacilityLifecycleService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

@RestController
@RequestMapping("/api/business/unit-type-changes")
@RequiredArgsConstructor
public class BusinessUnitTypeReviewController {
    private final ActorContext actors;
    private final FacilityLifecycleService lifecycle;
    @GetMapping
    public ApiResponse<List<UnitTypeChangeResponse>> list() {
        return new ApiResponse<>(lifecycle.listUnitTypeChanges(actors.required()), CorrelationIdContext.current());
    }
    @GetMapping("/{id}")
    public ApiResponse<UnitTypeChangeResponse> detail(@PathVariable UUID id) {
        return new ApiResponse<>(lifecycle.getUnitTypeChange(actors.required(), id), CorrelationIdContext.current());
    }
    public record Decision(@NotNull Boolean approved, @Size(max = 500) String reason,
        @PositiveOrZero java.math.BigDecimal monthlyPrice,
        @PositiveOrZero java.math.BigDecimal securityDepositAmount) {}
    @PostMapping("/{id}/review")
    public ApiResponse<UnitTypeChangeResponse> review(@PathVariable UUID id, @Valid @RequestBody Decision decision) {
        return new ApiResponse<>(lifecycle.reviewUnitTypeChange(actors.required(), id,
            decision.approved(), decision.reason(), decision.monthlyPrice(), decision.securityDepositAmount()), CorrelationIdContext.current());
    }
}
