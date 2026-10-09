package com.storagehub.api.rental;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.security.ActorContext;
import com.storagehub.service.RentalQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** D1 read model, kept separate from the shared Customer rental-list DTO. */
@RestController
@RequestMapping("/api/customer/rental-records")
@RequiredArgsConstructor
@Tag(name="D1 - Customer Rentals", description="Read-only rental records owned by the authenticated customer")
@SecurityRequirement(name="bearerAuth")
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400", description="Invalid or unsupported query"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="Invalid/revoked session"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="Customer role required"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409", description="Core rental data inconsistent")})
public class CustomerRentalReadController {
    private final ActorContext actors;
    private final RentalQueryService rentals;

    @GetMapping
    @Operation(summary="List my rental records", description="Owner is taken from the authenticated session. Unsupported/repeated query parameters return 400; no writes or PIN disclosure.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="Paginated owner-scoped RentalSummary records")
    @Parameters({
        @Parameter(name="page", in=ParameterIn.QUERY, schema=@Schema(type="integer", defaultValue="0", minimum="0")),
        @Parameter(name="size", in=ParameterIn.QUERY, schema=@Schema(type="integer", defaultValue="20", minimum="1", maximum="100")),
        @Parameter(name="status", in=ParameterIn.QUERY, schema=@Schema(allowableValues={"active","return_requested","return_inspection","closing","completed"})),
        @Parameter(name="search", in=ParameterIn.QUERY, description="Exact Rental UUID or literal unit-code substring; max 200 characters"),
        @Parameter(name="endFrom", in=ParameterIn.QUERY, schema=@Schema(type="string", format="date")),
        @Parameter(name="endTo", in=ParameterIn.QUERY, schema=@Schema(type="string", format="date")),
        @Parameter(name="sort", in=ParameterIn.QUERY, description="createdAt,contractEndDate,startDate,monthlyPrice,id / asc,desc", schema=@Schema(defaultValue="createdAt,desc"))})
    public PageResponse<RentalSummaryResponse> list(@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> params) {
        var actor = actors.required();
        return rentals.list(actor, RentalQuery.parse(params, false), false, CorrelationIdContext.current());
    }

    @GetMapping("/{id}")
    @Operation(summary="Read my rental record", description="404 for missing or other-owner records. Does not return raw access credentials.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", description="Owner-scoped RentalDetail")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404", description="Rental not found or not visible")
    public ApiResponse<RentalDetailResponse> detail(@PathVariable UUID id,
        @Parameter(hidden=true) @RequestParam MultiValueMap<String,String> params) {
        var actor = actors.required();
        RentalQuery.validateDetail(params);
        return new ApiResponse<>(rentals.detail(actor, id, false), CorrelationIdContext.current());
    }
}
