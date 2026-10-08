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
import java.util.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/customer") @RequiredArgsConstructor
@Tag(name="D2 - Customer Renewals",description="Persistent renewal workflow. Missing authoritative integrations return 409 DEFERRED_SOURCE; no policy defaults.")
@SecurityRequirement(name="bearerAuth")
public class CustomerRenewalController {
    private final ActorContext actors; private final RenewalReadService reads; private final RenewalWorkflowService commands;
    @GetMapping("/renewals") @Operation(summary="Read my renewal requests")
    @Parameters({@Parameter(name="page",in=ParameterIn.QUERY,example="0"),@Parameter(name="size",in=ParameterIn.QUERY,example="20"),@Parameter(name="status",in=ParameterIn.QUERY),@Parameter(name="rentalId",in=ParameterIn.QUERY),@Parameter(name="sort",in=ParameterIn.QUERY,example="createdAt,desc")})
    public PageResponse<RenewalResponse> list(@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){
        return reads.list(actors.required(),RenewalListQuery.parse(query,false),false,CorrelationIdContext.current());
    }
    @GetMapping("/renewals/{id}") @Operation(summary="Read my renewal detail",description="Unknown snapshot/version are null; financial state UNKNOWN")
    public ApiResponse<RenewalResponse> detail(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){
        noQuery(query);return new ApiResponse<>(reads.detail(actors.required(),id,false),CorrelationIdContext.current());
    }
    @GetMapping("/rentals/{id}/renewal-options") @Operation(summary="Eligible Renewal options",description="409 when authoritative shared policy/pricing is unavailable")
    @ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Eligible authoritative options"),@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="Missing shared source or business conflict")})
    public ApiResponse<List<RenewalCommands.Option>> options(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){
        noQuery(query);return new ApiResponse<>(commands.options(actors.required(),id),CorrelationIdContext.current());
    }
    @PostMapping("/rentals/{id}/renewal-quote") @Operation(summary="Create immutable Renewal quote",description="No capacity hold or Rental extension; requires authoritative sources")
    @ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Persisted immutable quote; no hold"),@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="Missing source or conflict")})
    public ApiResponse<RenewalQuoteResponse> quote(@PathVariable UUID id,@Valid @RequestBody RenewalCommands.Quote body){return new ApiResponse<>(commands.quote(actors.required(),id,body),CorrelationIdContext.current());}
    @PostMapping("/rentals/{id}/renewal-requests") @Operation(summary="Submit Renewal request",description="Accept quote and persist one open request; idempotent 201 replay")
    @ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="201",description="Persisted Renewal; replay returns original data"),@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="Expired quote, open request or source conflict")})
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ApiResponse<RenewalResponse> submit(@PathVariable UUID id,@Valid @RequestBody RenewalCommands.Submit body,@RequestHeader("Idempotency-Key") String key){return new ApiResponse<>(commands.submit(actors.required(),id,body,key),CorrelationIdContext.current());}
    @PatchMapping("/renewals/{id}") @Operation(summary="Accept revised Renewal quote",description="Pending only; preserve old revisions")
    @ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Accepted new immutable revision"),@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="Stale version/quote or dependency conflict")})
    public ApiResponse<RenewalResponse> edit(@PathVariable UUID id,@Valid @RequestBody RenewalCommands.Edit body,@RequestHeader("Idempotency-Key") String key){return new ApiResponse<>(commands.edit(actors.required(),id,body,key),CorrelationIdContext.current());}
    @PostMapping("/renewals/{id}/cancel") @Operation(summary="Cancel pending Renewal",description="Preserve history and release matching open slot; no refund")
    @ApiResponses({@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Pending request cancelled; no refund performed"),@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="Wrong phase or stale version")})
    public ApiResponse<RenewalResponse> cancel(@PathVariable UUID id,@Valid @RequestBody RenewalCommands.Cancel body,@RequestHeader("Idempotency-Key") String key){return new ApiResponse<>(commands.cancel(actors.required(),id,body,key),CorrelationIdContext.current());}
    private static void noQuery(MultiValueMap<String,String> query){if(!query.isEmpty())throw ApiExceptions.validation("Endpoint accepts no query parameters",null);}
}
