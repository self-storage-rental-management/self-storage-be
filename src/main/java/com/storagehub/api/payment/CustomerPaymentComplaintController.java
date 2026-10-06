package com.storagehub.api.payment;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.PaymentComplaintService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customer/payment-complaints")
@RequiredArgsConstructor
public class CustomerPaymentComplaintController {
    private final ActorContext actorContext;
    private final PaymentComplaintService complaintService;

    @PostMapping("/{complaintId}/withdraw")
    public ApiResponse<PaymentComplaintResponse> withdraw(@PathVariable UUID complaintId) {
        return new ApiResponse<>(
            complaintService.withdraw(actorContext.required(), complaintId),
            CorrelationIdContext.current()
        );
    }
}
