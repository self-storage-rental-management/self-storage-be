package com.storagehub.api.rental;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.RentalQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/manager/rentals")
@RequiredArgsConstructor
@Tag(name="D1 - Manager Rentals", description="Requires MANAGER + VIEW_RENTALS + facility READ scope")
@SecurityRequirement(name="bearerAuth")
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400", description="Invalid or unsupported query"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="Invalid/revoked session"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="Missing Manager role, VIEW_RENTALS or READ scope"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409", description="Core rental data inconsistent")})
public class ManagerRentalController {
    private final ActorContext actors;
    private final RentalQueryService rentals;
    private final com.storagehub.service.RentalSuspicionReportService suspicionReports;

    @GetMapping("/{id}/suspicion-reports")
    public ApiResponse<java.util.List<com.storagehub.service.RentalSuspicionReportService.Detail>> rentalSuspicionReports(@PathVariable UUID id) {
        return new ApiResponse<>(suspicionReports.managerRentalReports(actors.required(), id), CorrelationIdContext.current());
    }

    @PostMapping("/{id}/suspicion-reports")
    public ApiResponse<com.storagehub.service.RentalSuspicionReportService.Receipt> submitSuspicionReport(
        @PathVariable UUID id, @jakarta.validation.Valid @RequestBody com.storagehub.service.RentalSuspicionReportService.Input input) {
        return new ApiResponse<>(suspicionReports.submitAsManager(actors.required(), id, input), CorrelationIdContext.current());
    }

    @GetMapping("/suspicion-reports")
    public PageResponse<com.storagehub.service.RentalSuspicionReportService.Detail> suspicionReports(
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "15") int size) {
        return suspicionReports.managerList(actors.required(), page, size, CorrelationIdContext.current());
    }

    @PostMapping("/suspicion-reports/{reportId}/transfer")
    public ApiResponse<com.storagehub.service.RentalSuspicionReportService.ManagerDetail> transferSuspicionReport(
        @PathVariable UUID reportId, @jakarta.validation.Valid @RequestBody com.storagehub.service.RentalSuspicionReportService.TransferInput input) {
        return new ApiResponse<>(suspicionReports.transferToRecovery(actors.required(), reportId, input), CorrelationIdContext.current());
    }

    @PostMapping("/suspicion-reports/{reportId}/close")
    public ApiResponse<com.storagehub.service.RentalSuspicionReportService.ManagerDetail> closeSuspicionReport(
        @PathVariable UUID reportId, @jakarta.validation.Valid @RequestBody com.storagehub.service.RentalSuspicionReportService.CloseInput input) {
        return new ApiResponse<>(suspicionReports.closeWithoutViolation(actors.required(), reportId, input), CorrelationIdContext.current());
    }

    @PostMapping("/suspicion-reports/{reportId}/accept")
    public ApiResponse<com.storagehub.service.RentalSuspicionReportService.ManagerDetail> acceptSuspicionReport(@PathVariable UUID reportId) {
        return new ApiResponse<>(suspicionReports.accept(actors.required(), reportId), CorrelationIdContext.current());
    }

    @GetMapping("/suspicion-reports/{reportId}")
    public ApiResponse<com.storagehub.service.RentalSuspicionReportService.ManagerDetail> suspicionReport(@PathVariable UUID reportId) {
        return new ApiResponse<>(suspicionReports.managerDetail(actors.required(), reportId), CorrelationIdContext.current());
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="Paginated facility-scoped RentalSummary records")
    @Operation(summary="List rentals in my readable facilities", description="Omitting facilityId queries all READ scopes, not a random facility. Unsupported/repeated parameters return 400. Financial/access state remains UNKNOWN in D1.")
    @Parameters({
        @Parameter(name="facilityId", in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, schema=@Schema(type="string",format="uuid")),
        @Parameter(name="page", in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, schema=@Schema(type="integer",defaultValue="0",minimum="0")),
        @Parameter(name="size", in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, schema=@Schema(type="integer",defaultValue="20",minimum="1",maximum="100")),
        @Parameter(name="status", in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, schema=@Schema(allowableValues={"active","return_requested","return_inspection","closing","completed"})),
        @Parameter(name="search", in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, description="Exact SH display code, rental UUID or unit code/customer full-name substring; max 200 characters"),
        @Parameter(name="endFrom", in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, schema=@Schema(type="string",format="date")),
        @Parameter(name="endTo", in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, schema=@Schema(type="string",format="date")),
        @Parameter(name="sort", in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, description="One field,direction: createdAt,contractEndDate,startDate,monthlyPrice,id / asc,desc", schema=@Schema(defaultValue="createdAt,desc"))})
    public PageResponse<RentalSummaryResponse> list(@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> params) {
        var actor = actors.required();
        return rentals.list(actor, RentalQuery.parse(params, true), true, CorrelationIdContext.current());
    }

    @GetMapping("/{id}")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="Facility-scoped RentalDetail with UNKNOWN financial/access state")
    @Operation(summary="Read a scoped rental detail", description="404 for missing or outside-scope records. No query parameters; no raw access PIN.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404", description="Rental not found or not visible")
    public ApiResponse<RentalDetailResponse> detail(@PathVariable UUID id,
        @Parameter(hidden=true) @RequestParam MultiValueMap<String,String> params) {
        var actor = actors.required(); RentalQuery.validateDetail(params);
        return new ApiResponse<>(rentals.detail(actor, id, true), CorrelationIdContext.current());
    }
}
