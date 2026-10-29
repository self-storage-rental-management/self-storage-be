package com.storagehub.api.payment;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.domain.model.RefundRequestStatus;
import com.storagehub.security.ActorContext;
import com.storagehub.service.VnpayService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
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
@RequestMapping("/api/business/refund-requests")
@RequiredArgsConstructor
public class BusinessRefundRequestController {
    private final ActorContext actorContext;
    private final VnpayService service;

    @GetMapping
    public ApiResponse<List<RefundRequestResponse>> list(
        @RequestParam(required = false) RefundRequestStatus status
    ) {
        return new ApiResponse<>(service.listRefundRequests(actorContext.required(), status), CorrelationIdContext.current());
    }

    @PostMapping("/{requestId}/approve")
    public ApiResponse<VnpayTransactionResponse> approve(
        @PathVariable UUID requestId,
        @Valid @RequestBody(required = false) ApproveRefundRequest request,
        HttpServletRequest httpRequest
    ) {
        String ip = httpRequest.getHeader("X-Forwarded-For") != null
            ? httpRequest.getHeader("X-Forwarded-For") : httpRequest.getRemoteAddr();
        return new ApiResponse<>(service.approveRefundRequest(
            actorContext.required(), requestId, request == null ? null : request.resolutionNote(), ip
        ), CorrelationIdContext.current());
    }
}
