package com.storagehub.api.reservation;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.security.ActorContext;
import com.storagehub.service.ReservationGoodsReviewService;
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
@RequestMapping("/api/staff/reservation-reviews")
@RequiredArgsConstructor
public class StaffReservationReviewController {

    private final ActorContext actorContext;
    private final ReservationGoodsReviewService reviewService;

    @GetMapping
    public PageResponse<ReservationReviewResponse> listPending(
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return reviewService.listPending(
            actorContext.required(), facilityId, page, size, CorrelationIdContext.current()
        );
    }

    @PostMapping("/{reservationId}/decision")
    public ApiResponse<ReservationReviewResponse> review(
        @PathVariable UUID reservationId,
        @Valid @RequestBody ReservationReviewRequest request
    ) {
        return new ApiResponse<>(
            reviewService.review(actorContext.required(), reservationId, request),
            CorrelationIdContext.current()
        );
    }
}
