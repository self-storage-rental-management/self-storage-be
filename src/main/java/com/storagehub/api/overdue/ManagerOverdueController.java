package com.storagehub.api.overdue;

import com.storagehub.api.renewal.RenewalOperationQuery;
import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.overdue.OverdueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/manager/overdue-cases") @RequiredArgsConstructor
@SecurityRequirement(name="bearerAuth") @Tag(name="D4 - Facility Overdue",description="Read actual obligations and verified term policy. Missing sources mean PARTIAL/409, never zero debt. No fee assessment or access/unit release.")
@io.swagger.v3.oas.annotations.responses.ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="Stale revision/no longer overdue or DEFERRED_SOURCE"))
public class ManagerOverdueController {
    private final ActorContext actors;private final OverdueService service;
    @GetMapping @Operation(summary="Read scoped overdue cases with priority sorting",description="Query page,size,facilityId,kind=ALL|PAYMENT_DUE|RENTAL_TERM,search,sort=priority|overdueDays|caseRef,asc|desc. Completeness includes missing sources; server asOf only.")
    @io.swagger.v3.oas.annotations.Parameters({@Parameter(name="page",in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY,example="0"),@Parameter(name="size",in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY,example="20"),@Parameter(name="facilityId",in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY),@Parameter(name="kind",in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY,example="ALL"),@Parameter(name="search",in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY),@Parameter(name="sort",in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY,example="priority,desc")})
    public OverdueResponse.ListResult list(@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){return service.list(actors.required(),OverdueQuery.parse(query),CorrelationIdContext.current());}
    @GetMapping("/{id}") @Operation(summary="Read stable overdue reference")
    public ApiResponse<OverdueResponse> detail(@PathVariable String id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){RenewalOperationQuery.none(query);return new ApiResponse<>(service.detail(actors.required(),id),CorrelationIdContext.current());}
    @PostMapping("/{id}/follow-ups") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) @Operation(summary="Append note or queue policy-throttled reminder",description="Idempotent history only; does not modify debt. REMINDER requires real delivery/outbox adapter.")
    public ApiResponse<OverdueResponse.FollowUp> followUp(@PathVariable String id,@Valid @RequestBody OverdueCommands.FollowUp body,@RequestHeader("Idempotency-Key") String key){return new ApiResponse<>(service.followUp(actors.required(),id,body,key),CorrelationIdContext.current());}
    @GetMapping("/{id}/follow-ups") @Operation(summary="Read follow-up history even after case resolved")
    public PageResponse<OverdueResponse.FollowUp> history(@PathVariable String id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){var q=RenewalOperationQuery.parse(query);return service.history(actors.required(),id,q.page(),q.size(),CorrelationIdContext.current());}
    @PostMapping("/{id}/recovery-handoffs") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) @Operation(summary="Request actual Recovery receiver handoff",description="Only term expiry with verified Recovery start; unavailable receiver returns409. No debt-day8 inference, access lock, unit release or fake ACK.")
    public ApiResponse<OverdueResponse.FollowUp> recovery(@PathVariable String id,@Valid @RequestBody OverdueCommands.Recovery body,@RequestHeader("Idempotency-Key") String key){return new ApiResponse<>(service.recovery(actors.required(),id,body,key),CorrelationIdContext.current());}
}
