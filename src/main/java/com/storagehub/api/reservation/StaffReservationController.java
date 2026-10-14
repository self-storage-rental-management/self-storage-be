package com.storagehub.api.reservation;

import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.security.ActorContext;
import com.storagehub.service.StaffReservationQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/staff/reservations")
@RequiredArgsConstructor
public class StaffReservationController {

    private final ActorContext actorContext;
    private final StaffReservationQueryService service;

    @GetMapping
    public PageResponse<StaffReservationProgressResponse> listActive(
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(required = false) ReservationStatus status,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return service.listActive(
            actorContext.required(), facilityId, status, q, page, size,
            CorrelationIdContext.current()
        );
    }
}
