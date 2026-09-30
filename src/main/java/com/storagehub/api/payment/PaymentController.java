package com.storagehub.api.payment;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final ActorContext actorContext;
    private final PaymentService paymentService;

    @PostMapping("/intents")
    public ApiResponse<PaymentIntentResponse> createIntent(
        @Valid @RequestBody CreatePaymentIntentRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return new ApiResponse<>(
            paymentService.createIntent(actorContext.required(), request, idempotencyKey),
            CorrelationIdContext.current()
        );
    }
}
