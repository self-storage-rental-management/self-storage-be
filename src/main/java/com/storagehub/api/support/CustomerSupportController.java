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
import static com.storagehub.service.support.SupportService.Audience.CUSTOMER;

@RestController @RequestMapping("/api/customer/support-tickets") @RequiredArgsConstructor
@Tag(name="D5 - Customer Support",description="Owned ticket lifecycle. No internal messages, automatic refund or arbitrary facility/link ownership.")
@SecurityRequirement(name="bearerAuth")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="Validation/query/idempotency header error"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",description="Authentication required"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="Owned ticket/link not found"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="State/version/key conflict or DEFERRED_SOURCE")})
public class CustomerSupportController {
    private final ActorContext actors;private final SupportService service;
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary="Create owned Support ticket",description="Subject1–200, description1–4000, max10 distinct evidence UUIDs. Facility required without owned link. Evidence source missing rejects attachments, not text-only tickets. Customer cannot choose priority/internal visibility.")
    public ApiResponse<SupportResponse> create(@Valid @RequestBody SupportCommands.Create body,@RequestHeader("Idempotency-Key") String key){return response(service.create(actors.required(),body,key));}
    @GetMapping @Operation(summary="List my Support tickets",description="page0,size20(max100),status,search,sort=createdAt|updatedAt|subject|id,asc|desc. Ownership before pagination; legacy null-facility records require separate authorized triage.")
    @Parameters({@Parameter(name="page"),@Parameter(name="size"),@Parameter(name="status"),@Parameter(name="search"),@Parameter(name="sort")})
    public PageResponse<SupportResponse> list(@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> q){return service.list(actors.required(),CUSTOMER,SupportQuery.parse(q,false,false),CorrelationIdContext.current());}
    @GetMapping("/{id}") @Operation(summary="Read my Support detail",description="Messages are paginated separately; SLA/calendar unavailable is UNKNOWN, not zero or within SLA.")
    public ApiResponse<SupportResponse> detail(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> q){SupportQuery.none(q);return response(service.detail(actors.required(),CUSTOMER,id));}
    @GetMapping("/{id}/messages") @Operation(summary="Read public Support messages only",description="page,size. Internal messages and their counts/file references are excluded before pagination.")
    public PageResponse<SupportResponse.Message> messages(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> q){return service.messages(actors.required(),CUSTOMER,id,SupportQuery.parse(q,false,true),CorrelationIdContext.current());}
    @PostMapping("/{id}/messages") @ResponseStatus(HttpStatus.CREATED) @Operation(summary="Append public Customer reply",description="Idempotent. expectedVersion required when waiting_customer→in_progress. Reply does not reopen resolved; closed requires follow-up.")
    public ApiResponse<SupportResponse.Message> message(@PathVariable UUID id,@Valid @RequestBody SupportCommands.CustomerMessage body,@RequestHeader("Idempotency-Key") String key){return response(service.customerMessage(actors.required(),id,body,key));}
    @PostMapping("/{id}/close") @Operation(summary="Confirm resolution and close",description="Resolved only, expectedVersion, shared close policy and trusted escalation result required. No financial records are modified.")
    public ApiResponse<SupportResponse> close(@PathVariable UUID id,@Valid @RequestBody SupportCommands.Close body,@RequestHeader("Idempotency-Key") String key){return response(service.close(actors.required(),id,body,key));}
    @PostMapping("/{id}/reopen") @Operation(summary="Explicitly reopen an unresolved issue",description="Resolved (not closed), reason and expectedVersion. Retain eligible accepted Staff or return to open Manager queue; no silent reassignment.")
    public ApiResponse<SupportResponse> reopen(@PathVariable UUID id,@Valid @RequestBody SupportCommands.Reopen body,@RequestHeader("Idempotency-Key") String key){return response(service.reopen(actors.required(),id,body,key));}
    @PostMapping("/{id}/follow-ups") @ResponseStatus(HttpStatus.CREATED) @Operation(summary="Create linked follow-up for closed ticket",description="Validate ownership and same facility. No internal messages/evidence cloned; fields and link validated as a new ticket.")
    public ApiResponse<SupportResponse> followUp(@PathVariable UUID id,@Valid @RequestBody SupportCommands.Create body,@RequestHeader("Idempotency-Key") String key){return response(service.followUp(actors.required(),id,body,key));}
    private <T> ApiResponse<T> response(T value){return new ApiResponse<>(value,CorrelationIdContext.current());}
}
