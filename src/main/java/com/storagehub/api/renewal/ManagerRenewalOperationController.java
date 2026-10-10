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
import static com.storagehub.service.renewal.operations.RenewalOperationService.Audience.MANAGER;

@RestController @RequestMapping("/api/manager/renewals/{id}") @RequiredArgsConstructor
@SecurityRequirement(name="bearerAuth") @Tag(name="D3 - Manager Renewal Coordination",description="Read/review only. No Manager completion, arbitrary deadline override or payout.")
@io.swagger.v3.oas.annotations.responses.ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="Stale version/phase/deadline or DEFERRED_SOURCE"))
public class ManagerRenewalOperationController {
    private final ActorContext actors;private final RenewalOperationService service;
    @PostMapping("/staff-assignment") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) @Operation(summary="Phân công nhân viên xử lý gia hạn",description="Quản lý có quyền/phạm vi hiện hành; không thay phân công Booking/Check-in. Có version và Idempotency-Key.")
    public ApiResponse<RenewalOperationService.Result> assignment(@PathVariable UUID id,@Valid @RequestBody RenewalOperationCommands.StaffAssignment body,@RequestHeader("Idempotency-Key") String key){return response(service.assignStaff(actors.required(),id,body,key));}
    @PostMapping("/facility-fault-reviews") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) @Operation(summary="Xác minh sự cố thuộc trách nhiệm cơ sở",description="Ghi kết quả xác minh riêng; không tự đổi lịch, hoàn tiền hoặc duyệt ngoại lệ.")
    public ApiResponse<RenewalOperationService.Result> fault(@PathVariable UUID id,@Valid @RequestBody RenewalOperationCommands.FaultReview body,@RequestHeader("Idempotency-Key") String key){return response(service.reviewFault(actors.required(),id,body,key));}
    @GetMapping("/operations") @Operation(summary="Read facility Renewal operational state")
    public ApiResponse<RenewalOperationResponse> detail(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){RenewalOperationQuery.none(query);return new ApiResponse<>(service.detail(actors.required(),id,MANAGER),CorrelationIdContext.current());}
    @PostMapping("/exception-decisions") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) @Operation(summary="Review incident and propose bounded reschedule",description="Specialized permission required. Proposal awaits Customer confirmation; post-cutoff review does not pause Recovery.")
    public ApiResponse<RenewalOperationService.Result> exception(@PathVariable UUID id,@Valid @RequestBody RenewalOperationCommands.ExceptionDecision body,@RequestHeader("Idempotency-Key") String key){return response(service.exception(actors.required(),id,body,key));}
    @PostMapping("/refund-decisions") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) @Operation(summary="Reserve policy refund entitlement or reject",description="Approval is APPROVED_AWAITING_EXECUTION, not payout. Accounting reserves actual/pending entitlement; execution belongs to shared owner.")
    public ApiResponse<RenewalOperationService.Result> refund(@PathVariable UUID id,@Valid @RequestBody RenewalOperationCommands.RefundDecision body,@RequestHeader("Idempotency-Key") String key){return response(service.refund(actors.required(),id,body,key));}
    @GetMapping("/facility-incidents") @Operation(summary="Read incident and exception timeline")
    public PageResponse<RenewalOperationResponse.Event> incidents(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){return timeline(id,query,"facility-incidents");}
    @GetMapping("/payments") @Operation(summary="Read facility Renewal payment events")
    public PageResponse<RenewalOperationResponse.Event> payments(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){return timeline(id,query,"payments");}
    @GetMapping("/refunds") @Operation(summary="Read refund review reservations")
    public PageResponse<RenewalOperationResponse.Event> refunds(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){return timeline(id,query,"refunds");}
    private PageResponse<RenewalOperationResponse.Event> timeline(UUID id,MultiValueMap<String,String> query,String kind){var q=RenewalOperationQuery.parse(query);return service.events(actors.required(),id,MANAGER,kind,q.page(),q.size(),CorrelationIdContext.current());}
    private ApiResponse<RenewalOperationService.Result> response(RenewalOperationService.Result result){return new ApiResponse<>(result,CorrelationIdContext.current());}
}
