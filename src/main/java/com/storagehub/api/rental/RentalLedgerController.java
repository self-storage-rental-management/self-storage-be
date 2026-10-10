package com.storagehub.api.rental;

import com.storagehub.common.api.*;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.security.ActorContext;
import com.storagehub.service.ledger.LedgerQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @Tag(name="Sổ tài chính hồ sơ thuê")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="bearerAuth")
@ConditionalOnProperty(name="storagehub.integration.ledger.enabled",havingValue="true")
public class RentalLedgerController {
    private final ActorContext actors;
    private final LedgerQueryService queries;
    @GetMapping("/api/customer/rentals/{id}/ledger") @Operation(summary="Xem các khoản tài chính đã ghi nhận của hồ sơ thuê của mình")
    public ApiResponse<LedgerQueryService.View> customer(@PathVariable UUID id){return read(id,RoleCode.CUSTOMER);}
    @GetMapping("/api/manager/rentals/{id}/ledger") @Operation(summary="Xem sổ tài chính trong phạm vi cơ sở")
    public ApiResponse<LedgerQueryService.View> manager(@PathVariable UUID id){return read(id,RoleCode.MANAGER);}
    @GetMapping("/api/business/rentals/{id}/ledger") @Operation(summary="Xem sổ tài chính hồ sơ thuê")
    public ApiResponse<LedgerQueryService.View> business(@PathVariable UUID id){return read(id,RoleCode.BUSINESS);}
    private ApiResponse<LedgerQueryService.View> read(UUID id,RoleCode role){return new ApiResponse<>(queries.read(actors.required(),id,role),CorrelationIdContext.current());}
}
