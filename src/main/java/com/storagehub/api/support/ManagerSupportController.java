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
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.storagehub.service.support.SupportService.Audience.MANAGER;

@RestController @RequestMapping("/api/manager/support-tickets") @RequiredArgsConstructor
@Tag(name="D5 - Manager Support",description="Scoped coordination only. Manager cannot resolve as Staff or record receiver completion.")
@SecurityRequirement(name="bearerAuth")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",description="Support permission/role/scope missing"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="Ticket outside facility scope"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="Stale version/state/key conflict or DEFERRED_SOURCE")})
public class ManagerSupportController {
    private final ActorContext actors;private final SupportService service;
    @GetMapping @Operation(summary="Read scoped Support queue",description="page,size,status,facilityId,staffId,search,sort. Filter/scope applied before count/pagination.")
    @Parameters({@Parameter(name="page"),@Parameter(name="size"),@Parameter(name="status"),@Parameter(name="facilityId"),@Parameter(name="staffId"),@Parameter(name="search"),@Parameter(name="sort")})
    public PageResponse<SupportResponse> list(@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> q){return service.list(actors.required(),MANAGER,SupportQuery.parse(q,true,false),CorrelationIdContext.current());}
    @GetMapping("/staff-options") @Operation(summary="Read eligible Staff IDs at a managed facility",description="Active STAFF with persisted VIEW_SUPPORT + MANAGE_SUPPORT and OPERATE scope. No name-based matching or automatic permission grants. page,size only besides facilityId.")
    public PageResponse<SupportResponse.StaffOption> staff(@RequestParam UUID facilityId,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> q){var copy=new org.springframework.util.LinkedMultiValueMap<>(q);if(copy.get("facilityId").size()!=1)throw ApiExceptions.validation("Repeated facilityId",null);copy.remove("facilityId");return service.staffOptions(actors.required(),facilityId,SupportQuery.parse(copy,false,true),CorrelationIdContext.current());}
    @GetMapping("/{id}") @Operation(summary="Read scoped Support detail")
    public ApiResponse<SupportResponse> detail(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> q){SupportQuery.none(q);return response(service.detail(actors.required(),MANAGER,id));}
    @PostMapping("/{id}/assignment") @Operation(summary="Assign or reassign active Staff by UUID",description="MANAGE_SUPPORT+MANAGE scope, nonterminal, expectedVersion and reason. Reassignment resets acceptance to open, preserves history and revokes old Staff access.")
    public ApiResponse<SupportResponse> assign(@PathVariable UUID id,@Valid @RequestBody SupportCommands.Assignment body,@RequestHeader("Idempotency-Key") String key){return response(service.assign(actors.required(),id,body,key));}
    @GetMapping("/{id}/messages") @Operation(summary="Read scoped public/internal messages")
    public PageResponse<SupportResponse.Message> messages(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> q){return service.messages(actors.required(),MANAGER,id,SupportQuery.parse(q,false,true),CorrelationIdContext.current());}
    @GetMapping("/{id}/events") @Operation(summary="Read assignment/acceptance history")
    public PageResponse<SupportResponse.Event> events(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> q){return service.events(actors.required(),MANAGER,id,SupportQuery.parse(q,false,true),CorrelationIdContext.current());}
    @GetMapping("/{id}/escalations") @Operation(summary="Read internal coordination and verified receiver result")
    public PageResponse<SupportResponse.Escalation> escalations(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> q){return service.escalations(actors.required(),MANAGER,id,SupportQuery.parse(q,false,true),CorrelationIdContext.current());}
    @PostMapping("/{id}/escalations/{escalationId}/decision") @Operation(summary="Route or reject escalation",description="ROUTE is durable receiving/outbox reference, not successful result. REJECT returns coordination to Staff. No ACK/RECORD_RESULT input is accepted.")
    public ApiResponse<SupportResponse.Escalation> decision(@PathVariable UUID id,@PathVariable UUID escalationId,@Valid @RequestBody SupportCommands.EscalationDecision body,@RequestHeader("Idempotency-Key") String key){return response(service.decideEscalation(actors.required(),id,escalationId,body,key));}
    private <T> ApiResponse<T> response(T value){return new ApiResponse<>(value,CorrelationIdContext.current());}
}
