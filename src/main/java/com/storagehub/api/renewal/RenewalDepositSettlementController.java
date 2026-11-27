package com.storagehub.api.renewal;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.ledger.RenewalDepositSettlementService;
import com.storagehub.service.renewal.operations.RenewalOperationService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @Profile("shared-payment-schema")
@ConditionalOnProperty(name={"storagehub.integration.ledger.enabled","storagehub.integration.renewal-simple-payment.enabled"},havingValue="true")
public class RenewalDepositSettlementController {
    private final ActorContext actors;
    private final RenewalOperationService operations;
    private final RenewalDepositSettlementService deposits;
    @GetMapping("/api/{role:customer|manager|staff}/renewals/{id}/deposit-settlement")
    public ApiResponse<RenewalDepositSettlementService.View> read(@PathVariable String role,@PathVariable UUID id) {
        operations.detail(actors.required(),id,RenewalOperationService.Audience.valueOf(role.toUpperCase(Locale.ROOT)));
        return new ApiResponse<>(deposits.read(id),CorrelationIdContext.current());
    }
}
