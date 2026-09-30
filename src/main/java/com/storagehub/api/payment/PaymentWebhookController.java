package com.storagehub.api.payment;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks/payments")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentService paymentService;

    @PostMapping("/{provider}")
    public ApiResponse<PaymentIntentResponse> handle(
        @PathVariable String provider,
        @RequestHeader(value = "X-Signature", required = false) String signature,
        @RequestBody String rawBody
    ) {
        return new ApiResponse<>(paymentService.handleWebhook(provider, signature, rawBody), CorrelationIdContext.current());
    }
}
