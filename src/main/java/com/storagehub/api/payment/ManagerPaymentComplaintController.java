package com.storagehub.api.payment;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.security.ActorContext;
import com.storagehub.service.PaymentComplaintService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/manager/payment-complaints")
@RequiredArgsConstructor
public class ManagerPaymentComplaintController {
    private final ActorContext actorContext;
    private final PaymentComplaintService complaintService;

    @GetMapping
    public PageResponse<PaymentComplaintResponse> queue(
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return complaintService.managerQueue(
            actorContext.required(), facilityId, page, size, CorrelationIdContext.current()
        );
    }

    @GetMapping("/review-queue")
    public PageResponse<ManagerPaymentQueueItemResponse> reviewQueue(
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return complaintService.managerReviewQueue(
            actorContext.required(), facilityId, page, size, CorrelationIdContext.current()
        );
    }

    @GetMapping("/{complaintId}")
    public ApiResponse<PaymentComplaintResponse> detail(@PathVariable UUID complaintId) {
        return new ApiResponse<>(
            complaintService.managerDetail(actorContext.required(), complaintId),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{complaintId}/decision")
    public ApiResponse<PaymentComplaintResponse> decide(
        @PathVariable UUID complaintId,
        @Valid @RequestBody PaymentComplaintDecisionRequest request
    ) {
        return new ApiResponse<>(
            complaintService.decide(actorContext.required(), complaintId, request),
            CorrelationIdContext.current()
        );
    }
}
