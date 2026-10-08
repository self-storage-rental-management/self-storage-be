package com.storagehub.api.renewal;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.renewal.operations.RenewalOperationService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/staff/renewal-appointments") @RequiredArgsConstructor
@SecurityRequirement(name="bearerAuth") @Tag(name="D3 - Staff Renewal Operations")
public class StaffRenewalAppointmentController {
    private final ActorContext actors;private final RenewalOperationService service;
    @GetMapping @Operation(summary="Read current assigned signing appointments",description="Assignment and OPERATE scope filter before pagination. Date is DD-MM-independent ISO YYYY-MM-DD in system timezone.")
    @Parameters({@Parameter(name="page",in=ParameterIn.QUERY,example="0"),@Parameter(name="size",in=ParameterIn.QUERY,example="20"),@Parameter(name="facilityId",in=ParameterIn.QUERY),@Parameter(name="date",in=ParameterIn.QUERY,example="2026-10-08"),@Parameter(name="status",in=ParameterIn.QUERY,description="SIGNING|SIGNING_EXPIRED|COMPLETED")})
    public PageResponse<RenewalOperationResponse> list(@Parameter(hidden=true) @RequestParam MultiValueMap<String,String> query){
        if(query.entrySet().stream().anyMatch(e->!Set.of("page","size","facilityId","date","status").contains(e.getKey())||e.getValue().size()!=1))throw ApiExceptions.validation("Unsupported appointment query",null);
        var paging=new org.springframework.util.LinkedMultiValueMap<String,String>();for(String k:List.of("page","size"))if(query.containsKey(k))paging.put(k,query.get(k));var p=RenewalOperationQuery.parse(paging);
        UUID facility=null;LocalDate date=null;try{if(query.containsKey("facilityId"))facility=UUID.fromString(query.getFirst("facilityId"));if(query.containsKey("date"))date=LocalDate.parse(query.getFirst("date"));}catch(IllegalArgumentException|java.time.format.DateTimeParseException e){throw ApiExceptions.validation("Invalid appointment filter",null);}
        return service.appointments(actors.required(),facility,date,query.getFirst("status"),p.page(),p.size(),CorrelationIdContext.current());
    }
}
