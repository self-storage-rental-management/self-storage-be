package com.storagehub.api.reporting;

import com.storagehub.common.api.*;
import com.storagehub.security.ActorContext;
import com.storagehub.service.ManagerCashflowService;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/manager/reports")
@RequiredArgsConstructor
public class ManagerCashflowController {
    private final ActorContext actors;
    private final ManagerCashflowService service;
    @GetMapping("/cashflow")
    public ApiResponse<ManagerCashflowService.Report> cashflow(@RequestParam UUID facilityId,
        @RequestParam(required=false) LocalDate from, @RequestParam(required=false) LocalDate to) {
        return new ApiResponse<>(service.read(actors.required(), facilityId, from, to), CorrelationIdContext.current());
    }
}
