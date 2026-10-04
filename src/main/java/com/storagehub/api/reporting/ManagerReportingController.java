package com.storagehub.api.reporting;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.security.ActorContext;
import com.storagehub.service.ReportingService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager/reports")
@RequiredArgsConstructor
public class ManagerReportingController {

    private final ActorContext actorContext;
    private final ReportingService reportingService;

    @GetMapping("/summary")
    public ApiResponse<ManagerReportSummaryResponse> getSummary(
        @RequestParam(required = false) UUID facilityId
    ) {
        return new ApiResponse<>(
            reportingService.getManagerSummary(actorContext.required(), facilityId),
            CorrelationIdContext.current()
        );
    }

    @GetMapping("/activities")
    public PageResponse<FacilityActivityResponse> getActivities(
        @RequestParam(required = false) UUID facilityId,
        @RequestParam(required = false) String entityType,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        return reportingService.getFacilityActivities(
            actorContext.required(),
            facilityId,
            entityType,
            search,
            page,
            pageSize,
            CorrelationIdContext.current()
        );
    }
}
