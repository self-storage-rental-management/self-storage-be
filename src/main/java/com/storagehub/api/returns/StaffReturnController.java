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
@RequestMapping("/api/staff/returns")
@RequiredArgsConstructor
public class StaffReturnController {

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

    public record ArrivalRequest(
        java.time.Instant actualArrivedAt,
        @jakarta.validation.constraints.Size(max = 500) String evidenceNote,
        UUID evidenceId,
        Long expectedVersion
    ) {}

    @PostMapping("/{returnId}/arrival")
    public ApiResponse<ReturnCaseResponse> recordArrival(
        @PathVariable UUID returnId,
        @Valid @RequestBody(required = false) ArrivalRequest request
    ) {
        return new ApiResponse<>(returnSettlementService.recordReturnArrival(
            actorContext.required(),
            returnId,
            request == null ? null : request.actualArrivedAt(),
            request == null ? null : request.evidenceNote(),
            request == null ? null : request.evidenceId(),
            request == null ? null : request.expectedVersion()),
            CorrelationIdContext.current());
    }

    public record IdentityRequest(@jakarta.validation.constraints.NotNull UUID attemptId,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 32) String documentNumber,
        java.time.Instant reverificationStartedAt, UUID evidenceId) {}

    @PostMapping("/{returnId}/identity")
    public ApiResponse<ReturnCaseResponse> verifyIdentity(@PathVariable UUID returnId, @Valid @RequestBody IdentityRequest request) {
        return new ApiResponse<>(returnSettlementService.verifyReturnIdentity(actorContext.required(), returnId,
            request.attemptId(), request.documentNumber(), request.reverificationStartedAt(), request.evidenceId()), CorrelationIdContext.current());
    }

    @PostMapping("/{returnId}/property-check")
    public ApiResponse<ReturnCaseResponse> savePropertyCheck(@PathVariable UUID returnId,
        @Valid @RequestBody ReturnPropertyCheckRequest request) {
        return new ApiResponse<>(returnSettlementService.savePropertyCheck(actorContext.required(), returnId, request),
            CorrelationIdContext.current());
    }

    @PostMapping("/{returnId}/inspection")
    public ApiResponse<ReturnCaseResponse> performInspection(
        @PathVariable UUID returnId,
        @RequestParam(defaultValue = "0") long clearanceRevision,
        @Valid @RequestBody ReturnInspectionRequest request
    ) {
        return new ApiResponse<>(
            returnSettlementService.performInspection(actorContext.required(), returnId, request, clearanceRevision),
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
    public ApiResponse<ReturnCaseResponse> recordSettlement(@PathVariable UUID returnId,
        @Valid @RequestBody StaffReturnSettlementRequest request) {
        return new ApiResponse<>(returnSettlementService.recordSettlement(actorContext.required(),returnId,request),CorrelationIdContext.current());
    }

    @PostMapping("/scan-auto-settlement")
    public ApiResponse<java.util.Map<String, Object>> scanAutoSettlement() {
        int processed = returnSettlementService.autoSettleExpiredReturns(actorContext.required());
        return new ApiResponse<>(java.util.Map.of("processed", processed, "timestamp", java.time.Instant.now()), CorrelationIdContext.current());
    }
}
