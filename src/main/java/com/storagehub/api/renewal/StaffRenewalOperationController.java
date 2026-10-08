package com.storagehub.api.renewal;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.renewal.operations.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.storagehub.service.renewal.operations.RenewalOperationService.Audience.STAFF;

@RestController @RequestMapping("/api/staff/renewals/{id}") @RequiredArgsConstructor
@SecurityRequirement(name="bearerAuth") @Tag(name="D3 - Staff Renewal Operations",description="Requires owner-mapped signing permissions AND current assignment; PERFORM_CHECKIN is not reused.")
@io.swagger.v3.oas.annotations.responses.ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="Stale version/phase/deadline or DEFERRED_SOURCE"))
public class StaffRenewalOperationController {
    private final ActorContext actors;private final RenewalOperationService service;
    @GetMapping @Operation(summary="Read assigned Renewal operational detail")
    public ApiResponse<RenewalOperationResponse> detail(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){RenewalOperationQuery.none(query);return new ApiResponse<>(service.detail(actors.required(),id,STAFF),CorrelationIdContext.current());}
    @PostMapping("/arrival") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) @Operation(summary="Record arrival with server clock",description="No backdating, automatic completion or deadline extension.")
    public ApiResponse<RenewalOperationService.Result> arrival(@PathVariable UUID id,@Valid @RequestBody RenewalOperationCommands.Arrival body,@RequestHeader("Idempotency-Key") String key){return response(service.arrival(actors.required(),id,body,key));}
    @PostMapping("/facility-incidents") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) @Operation(summary="Record facility incident, including after expiry")
    public ApiResponse<RenewalOperationService.Result> incident(@PathVariable UUID id,@Valid @RequestBody RenewalOperationCommands.Incident body,@RequestHeader("Idempotency-Key") String key){return response(service.incident(actors.required(),id,body,key));}
    @GetMapping("/payable-statement") @Operation(summary="Read authoritative policy-required payable statement",description="Missing obligations never mean zero debt.")
    public ApiResponse<RenewalOperationSources.Statement> statement(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){RenewalOperationQuery.none(query);return new ApiResponse<>(service.statement(actors.required(),id),CorrelationIdContext.current());}
    @PostMapping("/cash-receipts") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) @Operation(summary="Attest actual CASH against current statement",description="Shared receipt/allocations remain recorded even if later completion fails. No arbitrary amount.")
    public ApiResponse<RenewalOperationService.Result> cash(@PathVariable UUID id,@Valid @RequestBody RenewalOperationCommands.Cash body,@RequestHeader("Idempotency-Key") String key){return response(service.cash(actors.required(),id,body,key));}
    @PostMapping("/completion") @Operation(summary="Complete signing and extend Rental exactly once",description="Requires identity, current arrival, signed evidence, full required payment, atomic hold/return/recovery checks and server deadline. Manager cannot complete.")
    public ApiResponse<RenewalOperationService.Result> complete(@PathVariable UUID id,@Valid @RequestBody RenewalOperationCommands.Completion body,@RequestHeader("Idempotency-Key") String key){return response(service.complete(actors.required(),id,body,key));}
    @GetMapping("/payments") @Operation(summary="Read assigned Renewal payment events")
    public PageResponse<RenewalOperationResponse.Event> payments(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){var q=RenewalOperationQuery.parse(query);return service.events(actors.required(),id,STAFF,"payments",q.page(),q.size(),CorrelationIdContext.current());}
    private ApiResponse<RenewalOperationService.Result> response(RenewalOperationService.Result result){return new ApiResponse<>(result,CorrelationIdContext.current());}
}
