package com.storagehub.api.reservation;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.security.ActorContext;
import com.storagehub.service.ReservationUnitAssignmentService;
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
public class StaffUnitAssignmentController {

    private final ActorContext actorContext;
    private final ReservationUnitAssignmentService assignmentService;

    @GetMapping
    public PageResponse<UnitAssignmentResponse> listAwaitingAssignment(
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return assignmentService.listAwaitingAssignment(
            actorContext.required(), facilityId, page, size, CorrelationIdContext.current()
        );
    }

    @PostMapping("/{reservationId}")
    public ApiResponse<UnitAssignmentResponse> assign(
        @PathVariable UUID reservationId,
        @Valid @RequestBody AssignStorageUnitRequest request
    ) {
        return new ApiResponse<>(
            assignmentService.assign(actorContext.required(), reservationId, request),
            CorrelationIdContext.current()
        );
    }
}
