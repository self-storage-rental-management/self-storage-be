package com.storagehub.api.renewal;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.VnpayService;
import com.storagehub.service.ledger.RenewalInstallmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController @RequiredArgsConstructor @SecurityRequirement(name="bearerAuth")
@Tag(name="Gia hạn - Thanh toán từng kỳ")
public class RenewalInstallmentController {
    private final ActorContext actors;
    private final ObjectProvider<RenewalInstallmentService> services;
    private final VnpayService vnpay;
    private RenewalInstallmentService service(){var s=services.getIfAvailable();if(s==null)throw ApiExceptions.conflict("Chưa bật sổ công nợ và thanh toán theo kỳ.");return s;}
    @GetMapping("/api/{role:customer|staff|manager}/renewals/{id}/payment-schedule")
    public ApiResponse<RenewalInstallmentService.Schedule> schedule(@PathVariable String role,@PathVariable UUID id){return new ApiResponse<>(service().schedule(actors.required(),id,role),CorrelationIdContext.current());}
    @PostMapping("/api/{role:customer|staff}/renewals/{id}/installment-payment-quotes")
    public ApiResponse<RenewalInstallmentService.Quote> quote(@PathVariable String role,@PathVariable UUID id,@Valid @RequestBody RenewalInstallmentCommands.Quote body){return new ApiResponse<>(service().quote(actors.required(),id,role,body),CorrelationIdContext.current());}
    @PostMapping("/api/customer/renewals/{id}/installment-payment-intents")
    public ApiResponse<com.storagehub.api.payment.RenewalVnpayPaymentResponse> intent(@PathVariable UUID id,@Valid @RequestBody RenewalInstallmentCommands.Intent body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){
        return new ApiResponse<>(vnpay.createInstallmentPaymentUrl(actors.required(),id,body.paymentQuoteId(),key,request.getRemoteAddr()),CorrelationIdContext.current());
    }
    @GetMapping("/api/{role:customer|staff|manager}/renewals/{id}/payments/{paymentId}")
    public ApiResponse<com.storagehub.api.payment.RenewalVnpayPaymentResponse> payment(@PathVariable String role,@PathVariable UUID id,@PathVariable UUID paymentId){return new ApiResponse<>(vnpay.installmentPayment(actors.required(),id,paymentId,role),CorrelationIdContext.current());}
    @PostMapping("/api/staff/renewals/{id}/installment-receipts")
    public ApiResponse<UUID> receipt(@PathVariable UUID id,@Valid @RequestBody RenewalInstallmentCommands.Receipt body,@RequestHeader("Idempotency-Key") String key){return new ApiResponse<>(service().receipt(actors.required(),id,body,key),CorrelationIdContext.current());}
}
