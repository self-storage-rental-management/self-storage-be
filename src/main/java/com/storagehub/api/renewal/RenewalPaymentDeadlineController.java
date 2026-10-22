package com.storagehub.api.renewal;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.renewal.operations.RenewalOperationService;
import com.storagehub.service.renewal.persistence.RenewalWorkflow;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @Transactional(readOnly=true)
public class RenewalPaymentDeadlineController {
    private final ActorContext actors;
    private final RenewalOperationService operations;
    private final EntityManager em;
    public record Deadlines(UUID renewalId,Instant prepaymentDueAt,Instant signingBalanceDueAt,Instant signingDeadline,Instant recoveryCutoff) {}
    @GetMapping("/api/customer/renewals/{id}/payment-deadlines")
    public ApiResponse<Deadlines> customer(@PathVariable UUID id){return read(id,RenewalOperationService.Audience.CUSTOMER);}
    @GetMapping("/api/staff/renewals/{id}/payment-deadlines")
    public ApiResponse<Deadlines> staff(@PathVariable UUID id){return read(id,RenewalOperationService.Audience.STAFF);}
    @GetMapping("/api/manager/renewals/{id}/payment-deadlines")
    public ApiResponse<Deadlines> manager(@PathVariable UUID id){return read(id,RenewalOperationService.Audience.MANAGER);}
    private ApiResponse<Deadlines> read(UUID id,RenewalOperationService.Audience audience) {
        var state=operations.detail(actors.required(),id,audience);var workflow=em.find(RenewalWorkflow.class,id);
        return new ApiResponse<>(new Deadlines(id,workflow==null?null:workflow.getPaymentDeadline(),workflow==null?null:workflow.getSigningBalanceDueAt(),state.effectiveSigningDeadline(),state.recoveryCutoff()),CorrelationIdContext.current());
    }
}
