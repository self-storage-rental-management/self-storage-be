package com.storagehub.api.returns;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.ReturnCaseStatus;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.security.ActorContext;
import com.storagehub.security.ActorPrincipal;
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

/** Business Operations may read returns and decide setup-exit refunds; Staff executes receipts. */
@RestController
@RequestMapping("/api/business/returns")
@RequiredArgsConstructor
public class BusinessReturnController {

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
            businessActor(), facilityId, status, q, page, pageSize, CorrelationIdContext.current()
        );
    }

    @GetMapping("/{returnId}")
    public ApiResponse<ReturnCaseResponse> getReturn(@PathVariable UUID returnId) {
        return new ApiResponse<>(
            returnSettlementService.getReturn(businessActor(), returnId),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{returnId}/setup-exit-decision")
    public ApiResponse<ReturnCaseResponse> decideSetupExit(
        @PathVariable UUID returnId,
        @Valid @RequestBody BusinessSetupExitDecisionRequest request
    ) {
        return new ApiResponse<>(
            returnSettlementService.businessReviewSetupExit(businessActor(), returnId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{returnId}/legacy-pricing-decision")
    public ApiResponse<ReturnCaseResponse> decideLegacyPricing(
        @PathVariable UUID returnId,
        @Valid @RequestBody BusinessLegacyPricingDecisionRequest request
    ) {
        return new ApiResponse<>(
            returnSettlementService.businessReviewLegacyPricing(businessActor(), returnId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/{returnId}/complete-refund")
    public ApiResponse<ReturnCaseResponse> completeRefund(
        @PathVariable UUID returnId,
        @Valid @RequestBody StaffCompleteRefundRequest request
    ) {
        return new ApiResponse<>(
            returnSettlementService.staffCompleteRefund(businessActor(), returnId, request),
            CorrelationIdContext.current()
        );
    }

    private ActorPrincipal businessActor() {
        ActorPrincipal actor = actorContext.required();
        if (!actor.hasRole(RoleCode.BUSINESS) && !actor.hasRole(RoleCode.ADMIN)) {
            throw com.storagehub.common.api.ApiExceptions.forbidden("Business Operations role is required");
        }
        return actor;
    }
}
