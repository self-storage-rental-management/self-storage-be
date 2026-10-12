package com.storagehub.api.renewal;

import com.storagehub.api.payment.RenewalVnpayPaymentResponse;
import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.VnpayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/customer/renewals/{renewalId}") @RequiredArgsConstructor
@SecurityRequirement(name="bearerAuth")
@Tag(name="D2 - Customer Renewal Payment",description="Full-rent VNPay Sandbox payment for the current accepted Renewal revision")
public class CustomerRenewalPaymentController {
    private final ActorContext actors;
    private final VnpayService payments;

    @PostMapping("/payment-intents")
    @Operation(summary="Create a resource-bound full-rent VNPay payment intent",description="Amount and accepted revision are resolved by the server. Readiness sources, financial obligations, Recovery cutoff and extension hold are rechecked in the write transaction.")
    public ApiResponse<RenewalVnpayPaymentResponse> create(
        @PathVariable UUID renewalId,
        @RequestHeader("Idempotency-Key") String key,
        HttpServletRequest request
    ) {
        String ip=request.getHeader("X-Forwarded-For")!=null?request.getHeader("X-Forwarded-For"):request.getRemoteAddr();
        return new ApiResponse<>(payments.createRenewalPaymentUrl(actors.required(),renewalId,key,ip),CorrelationIdContext.current());
    }

    @GetMapping("/payment")
    @Operation(summary="Read the latest Renewal VNPay payment result")
    public ApiResponse<RenewalVnpayPaymentResponse> get(@PathVariable UUID renewalId) {
        return new ApiResponse<>(payments.getRenewalPayment(actors.required(),renewalId),CorrelationIdContext.current());
    }
}
