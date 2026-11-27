package com.storagehub.api.rental;

import com.storagehub.common.api.ApiResponse;
import com.storagehub.common.api.CorrelationIdContext;
import com.storagehub.common.api.PageResponse;
import com.storagehub.domain.model.RoleCode;
import com.storagehub.security.ActorContext;
import com.storagehub.service.RentalQueryService;
import com.storagehub.service.RentalSuspicionReportService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/staff/rentals")
@RequiredArgsConstructor
public class StaffRentalController {
    private final ActorContext actors;
    private final RentalQueryService rentals;
    private final RentalSuspicionReportService suspicionReports;

    @GetMapping
    public PageResponse<RentalSummaryResponse> list(@RequestParam MultiValueMap<String, String> params) {
        return rentals.list(actors.required(), RentalQuery.parse(params, true), RoleCode.STAFF,
            CorrelationIdContext.current());
    }

    @GetMapping("/{id}")
    public ApiResponse<StaffRentalDetailResponse> detail(@PathVariable UUID id,
        @RequestParam MultiValueMap<String, String> params) {
        RentalQuery.validateDetail(params);
        return new ApiResponse<>(rentals.staffDetail(actors.required(), id), CorrelationIdContext.current());
    }

    @GetMapping("/{id}/suspicion-reports")
    public ApiResponse<java.util.List<RentalSuspicionReportService.Detail>> suspicionReports(@PathVariable UUID id) {
        return new ApiResponse<>(suspicionReports.staffList(actors.required(), id), CorrelationIdContext.current());
    }

    @GetMapping("/{id}/suspicion-reports/{reportId}")
    public ApiResponse<RentalSuspicionReportService.ManagerDetail> suspicionReport(@PathVariable UUID id, @PathVariable UUID reportId) {
        return new ApiResponse<>(suspicionReports.staffReportDetail(actors.required(), id, reportId), CorrelationIdContext.current());
    }

    @PostMapping("/{id}/suspicion-reports")
    public ApiResponse<RentalSuspicionReportService.Receipt> reportSuspicion(@PathVariable UUID id,
        @Valid @RequestBody RentalSuspicionReportService.Input input) {
        return new ApiResponse<>(suspicionReports.submit(actors.required(), id, input), CorrelationIdContext.current());
    }
}