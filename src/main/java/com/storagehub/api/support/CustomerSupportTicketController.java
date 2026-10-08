package com.storagehub.api.support;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.SupportTicketStatus;
import com.storagehub.security.ActorContext;
import com.storagehub.service.SupportTicketService;
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
@RequestMapping("/api/customer/support-tickets")
@RequiredArgsConstructor
public class CustomerSupportTicketController {

    private final ActorContext actorContext;
    private final SupportTicketService ticketService;

    @GetMapping
    public PageResponse<SupportTicketResponse> list(
        @RequestParam(required = false) SupportTicketStatus status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ticketService.listCustomer(actorContext.required(), status, page, size, CorrelationIdContext.current());
    }

    @PostMapping
    public ApiResponse<SupportTicketResponse> create(@Valid @RequestBody CreateSupportTicketRequest request) {
        return new ApiResponse<>(ticketService.createCustomer(actorContext.required(), request), CorrelationIdContext.current());
    }

    @GetMapping("/{ticketId}")
    public ApiResponse<SupportTicketResponse> get(@PathVariable UUID ticketId) {
        return new ApiResponse<>(ticketService.getCustomer(actorContext.required(), ticketId), CorrelationIdContext.current());
    }

    @PostMapping("/{ticketId}/messages")
    public ApiResponse<SupportTicketResponse> addMessage(
        @PathVariable UUID ticketId,
        @Valid @RequestBody CreateSupportMessageRequest request
    ) {
        return new ApiResponse<>(ticketService.addCustomerMessage(actorContext.required(), ticketId, request), CorrelationIdContext.current());
    }
}
