package com.storagehub.api.support;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.support.SupportService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.storagehub.service.support.SupportService.Audience.STAFF;

@RestController @RequestMapping("/api/staff/support-workflows") @RequiredArgsConstructor
@Tag(name="D5 - Assigned Staff Support",description="Current assignment, active STAFF, actual Support permission and OPERATE scope required. Default STAFF role is not silently granted MANAGE_SUPPORT.")
@SecurityRequirement(name="bearerAuth")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",description="Staff Support command permission missing"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="Not current assignee or out of scope"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="Assignment not accepted/stale version/state or DEFERRED_SOURCE")})
public class StaffSupportController {
    private final ActorContext actors;private final SupportService service;
    @GetMapping @Operation(summary="Read my assigned Support tickets",description="page,size,status,search,sort. Scope and current assignee before pagination.")
    @Parameters({@Parameter(name="page"),@Parameter(name="size"),@Parameter(name="status"),@Parameter(name="search"),@Parameter(name="sort")})
    public PageResponse<SupportResponse> list(@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> q){return service.list(actors.required(),STAFF,SupportQuery.parse(q,false,false),CorrelationIdContext.current());}
    @GetMapping("/{id}") @Operation(summary="Read current assigned Support detail")
    public ApiResponse<SupportResponse> detail(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> q){SupportQuery.none(q);return response(service.detail(actors.required(),STAFF,id));}
    @PostMapping("/{id}/accept") @Operation(summary="Accept current assignment",description="open→in_progress, expectedVersion, actor/timestamp persisted per assignment revision.")
    public ApiResponse<SupportResponse> accept(@PathVariable UUID id,@Valid @RequestBody SupportCommands.Version body,@RequestHeader("Idempotency-Key") String key){return response(service.accept(actors.required(),id,body,key));}
    @GetMapping("/{id}/messages") @Operation(summary="Read assigned public/internal messages")
    public PageResponse<SupportResponse.Message> messages(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> q){return service.messages(actors.required(),STAFF,id,SupportQuery.parse(q,false,true),CorrelationIdContext.current());}
    @PostMapping("/{id}/messages") @ResponseStatus(HttpStatus.CREATED) @Operation(summary="Append Staff reply or internal note",description="Accepted nonterminal assignment. PUBLIC updates reply timestamps and notifies Customer. INTERNAL does not notify Customer or change ticket status.")
    public ApiResponse<SupportResponse.Message> message(@PathVariable UUID id,@Valid @RequestBody SupportCommands.StaffMessage body,@RequestHeader("Idempotency-Key") String key){return response(service.staffMessage(actors.required(),id,body,key));}
    @PostMapping("/{id}/request-information") @Operation(summary="Request Customer information",description="in_progress→waiting_customer. Public question, expectedVersion and accepted assignment required. Internal waits use escalation, not waiting_customer.")
    public ApiResponse<SupportResponse> information(@PathVariable UUID id,@Valid @RequestBody SupportCommands.Information body,@RequestHeader("Idempotency-Key") String key){return response(service.requestInformation(actors.required(),id,body,key));}
    @PostMapping("/{id}/resolution") @Operation(summary="Resolve with verified outcome",description="Only accepted in_progress Staff. Linked records need objective-result adapter; unresolved escalation blocks. Never changes Rental/Payment/Refund/Return records.")
    public ApiResponse<SupportResponse> resolve(@PathVariable UUID id,@Valid @RequestBody SupportCommands.Resolution body,@RequestHeader("Idempotency-Key") String key){return response(service.resolve(actors.required(),id,body,key));}
    @PostMapping("/{id}/escalations") @ResponseStatus(HttpStatus.CREATED) @Operation(summary="Request authorized module escalation",description="Whitelisted module only. Missing receiving/result adapter rejects before creating a dead-end escalation. Evidence is INTERNAL; reason and expectedVersion required.")
    public ApiResponse<SupportResponse.Escalation> escalate(@PathVariable UUID id,@Valid @RequestBody SupportCommands.Escalate body,@RequestHeader("Idempotency-Key") String key){return response(service.escalate(actors.required(),id,body,key));}
    @GetMapping("/{id}/escalations") @Operation(summary="Read assigned escalation coordination/result")
    public PageResponse<SupportResponse.Escalation> escalations(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> q){return service.escalations(actors.required(),STAFF,id,SupportQuery.parse(q,false,true),CorrelationIdContext.current());}
    @GetMapping("/{id}/events") @Operation(summary="Read assigned workflow history")
    public PageResponse<SupportResponse.Event> events(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> q){return service.events(actors.required(),STAFF,id,SupportQuery.parse(q,false,true),CorrelationIdContext.current());}
    private <T> ApiResponse<T> response(T value){return new ApiResponse<>(value,CorrelationIdContext.current());}
}
