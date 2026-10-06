package com.storagehub.api.reporting;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.security.ActorContext;
import com.storagehub.service.ReportingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/business/reports")
@RequiredArgsConstructor
public class BusinessReportingController {

    private final ActorContext actorContext;
    private final ReportingService reportingService;

    @GetMapping("/revenue")
    public ApiResponse<BusinessRevenueReportResponse> getRevenueReport(
        @RequestParam(required = false, defaultValue = "6") Integer months
    ) {
        return new ApiResponse<>(
            reportingService.getBusinessRevenueReport(actorContext.required(), months),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/performance")
    public ApiResponse<BusinessPerformanceReportResponse> getPerformanceReport() {
        return new ApiResponse<>(
            reportingService.getBusinessPerformanceReport(actorContext.required()),
            CorrelationIdContext.current()
        );
    }
}
