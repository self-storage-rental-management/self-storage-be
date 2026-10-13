package com.storagehub.api.payment;

import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.security.ActorContext;
import com.storagehub.service.ManagerPaymentHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager/payment-history")
@RequiredArgsConstructor
public class ManagerPaymentHistoryController {
    private final ActorContext actors;
    private final ManagerPaymentHistoryService paymentHistory;

    @GetMapping
    public PageResponse<ManagerPaymentHistoryResponse> list(
        @RequestParam MultiValueMap<String, String> query
    ) {
        ManagerPaymentHistoryQuery parsed = ManagerPaymentHistoryQuery.parse(query);
        return paymentHistory.list(actors.required(), parsed, CorrelationIdContext.current());
    }
}
