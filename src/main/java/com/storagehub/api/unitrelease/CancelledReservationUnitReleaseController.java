package com.storagehub.api.unitrelease;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.security.ActorContext;
import com.storagehub.service.CancelledReservationUnitReleaseService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/staff/cancelled-reservations")
@RequiredArgsConstructor
public class CancelledReservationUnitReleaseController {

    private final ActorContext actorContext;
    private final CancelledReservationUnitReleaseService service;

    @GetMapping("/unit-releases")
    public PageResponse<UnitReleaseCaseResponse> list(
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(required = false) UUID unitTypeId,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "false") Boolean blocked,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        return service.list(
            actorContext.required(), facilityId, unitTypeId, q, blocked, page, pageSize,
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/{reservationId}/unit-release")
    public ApiResponse<UnitReleaseCaseResponse> get(@PathVariable UUID reservationId) {
        return new ApiResponse<>(
            service.get(actorContext.required(), reservationId),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{reservationId}/unit-release")
    public ApiResponse<UnitReleaseResponse> release(
        @PathVariable UUID reservationId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody ReleaseAssignedUnitRequest request
    ) {
        return new ApiResponse<>(
            service.release(actorContext.required(), reservationId, idempotencyKey, request),
            CorrelationIdContext.current()
        );
    }
}
