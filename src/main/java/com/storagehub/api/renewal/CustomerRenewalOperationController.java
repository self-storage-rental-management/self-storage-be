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
import static com.storagehub.service.renewal.operations.RenewalOperationService.Audience.CUSTOMER;

@RestController @RequestMapping("/api/customer/renewals/{id}") @RequiredArgsConstructor
@SecurityRequirement(name="bearerAuth") @Tag(name="D3 - Customer Renewal Operations",description="409 DEFERRED_SOURCE until accounting/calendar/hold adapters are ready. No synthetic payments.")
@io.swagger.v3.oas.annotations.responses.ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="Stale version/phase/deadline or DEFERRED_SOURCE; no synthetic success"))
public class CustomerRenewalOperationController {
    private final ActorContext actors;private final RenewalOperationService service;
    @GetMapping("/operations") @Operation(summary="Read my signing deadlines and operational state")
    public ApiResponse<RenewalOperationResponse> detail(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){RenewalOperationQuery.none(query);return new ApiResponse<>(service.detail(actors.required(),id,CUSTOMER),CorrelationIdContext.current());}
    @GetMapping("/exception-proposal") @Operation(summary="Read my current proposed signing appointment and deadline",description="Customer-safe projection only, never the internal incident timeline. Missing sources disable confirmation; the command rechecks ownership, version, proposal, policy, calendar and cutoff.")
    public ApiResponse<RenewalExceptionProposalResponse> exceptionProposal(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){RenewalOperationQuery.none(query);return new ApiResponse<>(service.exceptionProposal(actors.required(),id),CorrelationIdContext.current());}
    @PostMapping("/simulated-payment") @Operation(summary="Pay approved Renewal DEPOSIT only",description="Amount/outcome determined by shared engine. Does not extend Rental. No Customer BALANCE/CASH.")
    public ApiResponse<RenewalOperationService.Result> deposit(@PathVariable UUID id,@Valid @RequestBody RenewalOperationCommands.Version body,@RequestHeader("Idempotency-Key") String key){return response(service.deposit(actors.required(),id,body,key));}
    @PostMapping("/appointment") @Operation(summary="Book a signing slot within verified deadlines")
    public ApiResponse<RenewalOperationService.Result> appointment(@PathVariable UUID id,@Valid @RequestBody RenewalOperationCommands.Appointment body,@RequestHeader("Idempotency-Key") String key){return response(service.appointment(actors.required(),id,body,key,false));}
    @PatchMapping("/appointment") @Operation(summary="Reschedule signing without resetting deadline")
    public ApiResponse<RenewalOperationService.Result> reschedule(@PathVariable UUID id,@Valid @RequestBody RenewalOperationCommands.Appointment body,@RequestHeader("Idempotency-Key") String key){return response(service.appointment(actors.required(),id,body,key,true));}
    @GetMapping("/appointment") @Operation(summary="Read current signing appointment")
    public ApiResponse<RenewalOperationResponse> appointmentDetail(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){return detail(id,query);}
    @PostMapping("/exception-confirmations") @Operation(summary="Confirm latest bounded facility reschedule",description="Rechecks calendar, hold, policy and cutoff; cannot revive terminal requests or pause Recovery.")
    public ApiResponse<RenewalOperationService.Result> confirm(@PathVariable UUID id,@Valid @RequestBody RenewalOperationCommands.Confirmation body,@RequestHeader("Idempotency-Key") String key){return response(service.confirm(actors.required(),id,body,key));}
    @GetMapping("/payments") @Operation(summary="Read persisted Renewal payment/receipt events")
    public PageResponse<RenewalOperationResponse.Event> payments(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){return timeline(id,query,"payments");}
    @GetMapping("/refunds") @Operation(summary="Read refund review reservations, not proof of payout")
    public PageResponse<RenewalOperationResponse.Event> refunds(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){return timeline(id,query,"refunds");}
    private PageResponse<RenewalOperationResponse.Event> timeline(UUID id,MultiValueMap<String,String> query,String kind){var q=RenewalOperationQuery.parse(query);return service.events(actors.required(),id,CUSTOMER,kind,q.page(),q.size(),CorrelationIdContext.current());}
    private ApiResponse<RenewalOperationService.Result> response(RenewalOperationService.Result result){return new ApiResponse<>(result,CorrelationIdContext.current());}
}
