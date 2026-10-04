package com.storagehub.api.returns;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
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
@RequestMapping("/api/customer")
@RequiredArgsConstructor
public class CustomerReturnController {

    private final ActorContext actorContext;
    private final ReturnSettlementService returnSettlementService;

    @PostMapping("/rentals/{rentalId}/return-request")
    public ApiResponse<ReturnCaseResponse> createReturnRequest(
        @PathVariable UUID rentalId,
        @Valid @RequestBody CustomerCreateReturnRequest request
    ) {
        return new ApiResponse<>(
            returnSettlementService.createCustomerReturnRequest(actorContext.required(), rentalId, request),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/returns")
    public PageResponse<ReturnCaseResponse> listReturns(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        return returnSettlementService.listReturnsForCustomer(
            actorContext.required(), page, pageSize, CorrelationIdContext.current()
        );
    }

    @GetMapping("/returns/{returnId}")
    public ApiResponse<ReturnCaseResponse> getReturn(@PathVariable UUID returnId) {
        return new ApiResponse<>(
            returnSettlementService.getReturnForCustomer(actorContext.required(), returnId),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/returns/{returnId}/confirm")
    public ApiResponse<ReturnCaseResponse> confirmSettlement(
        @PathVariable UUID returnId,
        @Valid @RequestBody CustomerConfirmSettlementRequest request
    ) {
        return new ApiResponse<>(
            returnSettlementService.customerConfirmSettlement(actorContext.required(), returnId, request),
            CorrelationIdContext.current()
        );
    }

    @PostMapping("/returns/{returnId}/pay-settlement")
    public ApiResponse<ReturnCaseResponse> paySettlement(
        @PathVariable UUID returnId,
        @Valid @RequestBody CustomerPaySettlementRequest request
    ) {
        return new ApiResponse<>(
            returnSettlementService.customerPaySettlement(actorContext.required(), returnId, request),
            CorrelationIdContext.current()
        );
    }
}
