package com.storagehub.api.checkin;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.security.ActorContext;
import com.storagehub.service.CheckInHandoverService;
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
@RequestMapping("/api/staff/check-ins")
@RequiredArgsConstructor
public class CheckInController {

    private final ActorContext actorContext;
    private final CheckInHandoverService service;

    @GetMapping
    public PageResponse<CheckInResponse> list(
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        return service.list(
            actorContext.required(), facilityId, q, page, pageSize,
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/reservations/{reservationId}/schedule")
    public ApiResponse<CheckInResponse> schedule(
        @PathVariable UUID reservationId,
        @Valid @RequestBody ScheduleCheckInRequest request
    ) {
        return new ApiResponse<>(
            service.schedule(actorContext.required(), reservationId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{checkInId}/complete")
    public ApiResponse<CheckInResponse> complete(
        @PathVariable UUID checkInId,
        @Valid @RequestBody CompleteCheckInRequest request
    ) {
        return new ApiResponse<>(
            service.complete(actorContext.required(), checkInId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{checkInId}/no-show")
    public ApiResponse<CheckInResponse> markNoShow(
        @PathVariable UUID checkInId,
        @Valid @RequestBody MarkNoShowRequest request
    ) {
        return new ApiResponse<>(
            service.markNoShow(actorContext.required(), checkInId, request),
            CorrelationIdContext.current()
        );
    }
}
