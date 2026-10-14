package com.storagehub.api.rental;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.ledger.SandboxPaymentReconciliationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.sandbox-reconciliation.enabled"},havingValue="true")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bearerAuth")
public class SandboxPaymentReconciliationController {
    public record Command(@NotNull @PositiveOrZero Long expectedRevision) {}
    private final ActorContext actors;
    private final SandboxPaymentReconciliationService service;
    @PostMapping("/api/manager/rentals/{id}/sandbox-payments/{paymentId}/reconciliation")
    public ApiResponse<SandboxPaymentReconciliationService.Result> reconcile(@PathVariable UUID id,@PathVariable UUID paymentId,
        @Valid @RequestBody Command body,@RequestHeader("Idempotency-Key") String key) {
        return new ApiResponse<>(service.reconcile(actors.required(),id,paymentId,body.expectedRevision(),key),CorrelationIdContext.current());
    }
}
