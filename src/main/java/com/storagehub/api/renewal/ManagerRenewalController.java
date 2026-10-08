package com.storagehub.api.renewal;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.renewal.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.util.UUID;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/manager/renewals") @RequiredArgsConstructor
@Tag(name="D2 - Manager Renewals",description="VIEW_RENTALS + READ for queries; MANAGE_RENTALS + MANAGE for decision. Decisions remain gated, no fabricated hold/payment.")
@SecurityRequirement(name="bearerAuth")
public class ManagerRenewalController {
    private final ActorContext actors; private final RenewalReadService reads; private final RenewalWorkflowService commands;
    @GetMapping @Operation(summary="List scoped renewal requests")
    @Parameters({@Parameter(name="page",in=ParameterIn.QUERY,example="0"),@Parameter(name="size",in=ParameterIn.QUERY,example="20"),@Parameter(name="status",in=ParameterIn.QUERY),@Parameter(name="rentalId",in=ParameterIn.QUERY),@Parameter(name="facilityId",in=ParameterIn.QUERY),@Parameter(name="search",in=ParameterIn.QUERY),@Parameter(name="sort",in=ParameterIn.QUERY,example="createdAt,desc")})
    public PageResponse<RenewalResponse> list(@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){
        return reads.list(actors.required(),RenewalListQuery.parse(query,true),true,CorrelationIdContext.current());
    }
    @GetMapping("/{id}") @Operation(summary="Read scoped renewal detail")
    public ApiResponse<RenewalResponse> detail(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){
        if(!query.isEmpty())throw ApiExceptions.validation("Detail accepts no query parameters",null);
        return new ApiResponse<>(reads.detail(actors.required(),id,true),CorrelationIdContext.current());
    }
    @PostMapping("/{id}/decision") @Operation(summary="Review pending Renewal",description="Reject with reason; approve with authoritative policy/finance and transaction-participating shared hold. Never extends Rental in D2")
    @ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Persisted decision and audit; approval does not extend Rental"),@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="Missing dependency, changed terms or stale version")})
    public ApiResponse<RenewalResponse> decision(@PathVariable UUID id,@Valid @RequestBody RenewalCommands.Decision body,@RequestHeader("Idempotency-Key") String key){return new ApiResponse<>(commands.decision(actors.required(),id,body,key),CorrelationIdContext.current());}
}
