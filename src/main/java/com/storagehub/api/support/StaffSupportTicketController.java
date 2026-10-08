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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/staff/support-tickets")
@RequiredArgsConstructor
public class StaffSupportTicketController {

    private final ActorContext actorContext;
    private final SupportTicketService ticketService;

    @GetMapping
    public PageResponse<SupportTicketResponse> list(
        @RequestParam(required = false) SupportTicketStatus status,
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ticketService.listStaff(actorContext.required(), status, facilityId, q, page, size, CorrelationIdContext.current());
    }

    @PatchMapping("/{ticketId}")
    public ApiResponse<SupportTicketResponse> update(
        @PathVariable UUID ticketId,
        @Valid @RequestBody UpdateSupportTicketRequest request
    ) {
        return new ApiResponse<>(ticketService.updateByStaff(actorContext.required(), ticketId, request), CorrelationIdContext.current());
    }
}
