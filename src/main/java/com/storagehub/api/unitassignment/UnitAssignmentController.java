package com.storagehub.api.unitassignment;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.security.ActorContext;
import com.storagehub.service.UnitAssignmentService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/staff/unit-assignments")
@RequiredArgsConstructor
public class UnitAssignmentController {

    private final ActorContext actorContext;
    private final UnitAssignmentService service;

    @GetMapping("/candidates")
    public PageResponse<UnitAssignmentCandidateResponse> listCandidates(
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(required = false) UUID unitTypeId,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        return service.listCandidates(
            actorContext.required(), facilityId, unitTypeId, q, page, pageSize,
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/candidates/{reservationId}/available-units")
    public PageResponse<AssignableUnitResponse> listAvailableUnits(
        @PathVariable UUID reservationId,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "100") int pageSize
    ) {
        return service.listAvailableUnits(
            actorContext.required(), reservationId, q, page, pageSize,
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{reservationId}")
    public ApiResponse<UnitAssignmentResponse> assign(
        @PathVariable UUID reservationId,
        @Valid @RequestBody CreateUnitAssignmentRequest request
    ) {
        return new ApiResponse<>(
            service.assign(actorContext.required(), reservationId, request),
            CorrelationIdContext.current()
        );
    }
}
