package com.storagehub.api.payment;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.VnpayService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager/payments")
@RequiredArgsConstructor
public class ManagerVnpayController {
    private final ActorContext actorContext;
    private final VnpayService service;

    @PostMapping("/{paymentId}/vnpay/query")
    public ApiResponse<VnpayTransactionResponse> query(@PathVariable UUID paymentId, HttpServletRequest request) {
        return new ApiResponse<>(service.query(actorContext.required(), paymentId, ip(request)), CorrelationIdContext.current());
    }

    @PostMapping("/{paymentId}/vnpay/refund")
    public ApiResponse<VnpayTransactionResponse> refund(
        @PathVariable UUID paymentId,
        @Valid @RequestBody VnpayRefundRequest body,
        HttpServletRequest request
    ) {
        return new ApiResponse<>(service.refund(actorContext.required(), paymentId, body, ip(request)), CorrelationIdContext.current());
    }

    private String ip(HttpServletRequest request) {
        return request.getHeader("X-Forwarded-For") != null
            ? request.getHeader("X-Forwarded-For") : request.getRemoteAddr();
    }
}
