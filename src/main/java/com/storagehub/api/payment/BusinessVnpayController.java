package com.storagehub.api.payment;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.security.ActorPrincipal;
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
@RequestMapping("/api/business/payments")
@RequiredArgsConstructor
public class BusinessVnpayController {
    private final ActorContext actorContext;
    private final VnpayService service;

    @PostMapping("/{paymentId}/vnpay/refund")
    public ApiResponse<VnpayTransactionResponse> refund(
        @PathVariable UUID paymentId,
        @Valid @RequestBody VnpayRefundRequest body,
        HttpServletRequest request
    ) {
        ActorPrincipal actor = actorContext.required();
        return new ApiResponse<>(service.refund(actor, paymentId, body,
            request.getHeader("X-Forwarded-For") != null ? request.getHeader("X-Forwarded-For") : request.getRemoteAddr()),
            CorrelationIdContext.current());
    }
}
