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
@RequestMapping("/api/customer/rentals")
@RequiredArgsConstructor
@Tag(name = "D1 - Customer Rentals", description = "Read-only rental records owned by the authenticated Customer")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400", description="Invalid or unsupported query"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="Invalid/revoked session"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="Customer role required"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409", description="Core rental data inconsistent")})
public class CustomerRentalController {
    private final ActorContext actors;
    private final RentalQueryService rentals;

    @GetMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="Paginated owned RentalSummary records")
    @Operation(summary="List my rental records", description="D1: financial/access state is not inferred. Unsupported parameters (including needsAttention) and repeated scalar parameters return 400.")
    @Parameters({
        @Parameter(name="page", in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, schema=@Schema(type="integer", defaultValue="0", minimum="0")),
        @Parameter(name="size", in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, schema=@Schema(type="integer", defaultValue="20", minimum="1", maximum="100")),
        @Parameter(name="status", in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, schema=@Schema(allowableValues={"active","return_requested","return_inspection","closing","completed"})),
        @Parameter(name="search", in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, description="Exact Rental UUID or case-insensitive unit-code substring; max 200 characters"),
        @Parameter(name="endFrom", in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, schema=@Schema(type="string", format="date")),
        @Parameter(name="endTo", in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, schema=@Schema(type="string", format="date")),
        @Parameter(name="sort", in=io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY, description="One field,direction: createdAt,contractEndDate,startDate,monthlyPrice,id / asc,desc; stable ID tie-break", schema=@Schema(defaultValue="createdAt,desc"))})
    public PageResponse<RentalSummaryResponse> list(@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> params) {
        var actor = actors.required();
        return rentals.list(actor, RentalQuery.parse(params, false), false, CorrelationIdContext.current());
    }

    @GetMapping("/{id}")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="Owned RentalDetail with UNKNOWN financial/access state")
    @Operation(summary="Read my rental detail", description="404 for missing or non-owned records. No query parameters; no PIN or legal contract/debug data.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404", description="Rental not found or not visible")
    public ApiResponse<RentalDetailResponse> detail(@PathVariable UUID id,
        @Parameter(hidden=true) @RequestParam MultiValueMap<String,String> params) {
        var actor = actors.required(); RentalQuery.validateDetail(params);
        return new ApiResponse<>(rentals.detail(actor, id, false), CorrelationIdContext.current());
    }
}
