package com.storagehub.api.returns;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.ReturnCaseStatus;
import com.storagehub.security.ActorContext;
import com.storagehub.service.ReturnSettlementService;
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
@RequestMapping("/api/manager/returns")
@RequiredArgsConstructor
public class ManagerReturnController {

    private final ActorContext actorContext;
    private final ReturnSettlementService returnSettlementService;

    @GetMapping
    public PageResponse<ReturnCaseResponse> listReturns(
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(required = false) ReturnCaseStatus status,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        return returnSettlementService.listReturns(
            actorContext.required(), facilityId, status, q, page, pageSize, CorrelationIdContext.current()
        );
    }

    @GetMapping("/{returnId}")
    public ApiResponse<ReturnCaseResponse> getReturn(@PathVariable UUID returnId) {
        return new ApiResponse<>(
            returnSettlementService.getReturn(actorContext.required(), returnId),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{returnId}/review-dispute")
    public ApiResponse<ReturnCaseResponse> reviewDispute(
        @PathVariable UUID returnId,
        @Valid @RequestBody ManagerReviewDisputeRequest request
    ) {
        return new ApiResponse<>(
            returnSettlementService.managerReviewDispute(actorContext.required(), returnId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{returnId}/complete-refund")
    public ApiResponse<ReturnCaseResponse> completeRefund(
        @PathVariable UUID returnId,
        @Valid @RequestBody(required = false) StaffCompleteRefundRequest request
    ) {
        StaffCompleteRefundRequest safeRequest = request != null ? request : new StaffCompleteRefundRequest(null, null);
        return new ApiResponse<>(
            returnSettlementService.staffCompleteRefund(actorContext.required(), returnId, safeRequest),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{returnId}/settlement-receipts")
    public ApiResponse<ReturnCaseResponse> recordSettlement(
        @PathVariable UUID returnId,
        @Valid @RequestBody StaffReturnSettlementRequest request
    ) {
        return new ApiResponse<>(
            returnSettlementService.recordSettlement(actorContext.required(), returnId, request),
            CorrelationIdContext.current()
        );
    }
}
