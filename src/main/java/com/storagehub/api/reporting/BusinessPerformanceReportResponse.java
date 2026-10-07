package com.storagehub.api.reporting;

import java.util.List;
import java.util.UUID;

public record BusinessPerformanceReportResponse(
    long totalFacilities,
    long totalCapacityUnits,
    long totalOccupiedUnits,
    double systemOccupancyRate,
    List<FacilityPerformance> facilityComparisons
) {
    public record FacilityPerformance(
        UUID facilityId,
        String facilityName,
        String city,
        long totalUnits,
        long occupiedUnits,
        double occupancyRate,
        long activeRentals,
        long openReturns,
        long openMaintenance
    ) {}
}
