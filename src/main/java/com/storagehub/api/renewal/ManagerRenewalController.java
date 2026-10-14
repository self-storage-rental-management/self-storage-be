package com.storagehub.api.renewal;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.renewal.RenewalReadService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.RequiredArgsConstructor;
import java.util.UUID;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/manager/renewals") @RequiredArgsConstructor
@Tag(name="D2 - Manager Renewals",description="Manager can list and read scoped Renewal requests for monitoring. Renewal payment readiness is system-validated; Manager approval is not required.")
@SecurityRequirement(name="bearerAuth")
public class ManagerRenewalController {
    private final ActorContext actors; private final RenewalReadService reads;
    @GetMapping @Operation(summary="List scoped renewal requests")
    @Parameters({@Parameter(name="page",in=ParameterIn.QUERY,example="0"),@Parameter(name="size",in=ParameterIn.QUERY,example="20"),@Parameter(name="status",in=ParameterIn.QUERY),@Parameter(name="rentalId",in=ParameterIn.QUERY),@Parameter(name="facilityId",in=ParameterIn.QUERY),@Parameter(name="search",in=ParameterIn.QUERY),@Parameter(name="sort",in=ParameterIn.QUERY,example="createdAt,desc")})
    public PageResponse<RenewalResponse> list(@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){
        return reads.list(actors.required(),RenewalListQuery.parse(query,true),true,CorrelationIdContext.current());
    }
    @GetMapping("/{id}") @Operation(summary="Read scoped renewal detail")
    public ApiResponse<RenewalResponse> detail(@PathVariable UUID id,@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){
        if(!query.isEmpty())throw ApiExceptions.validation("Detail accepts no query parameters",null);
        return new ApiResponse<>(reads.detail(actors.required(),id,true),CorrelationIdContext.current());
    }
}
