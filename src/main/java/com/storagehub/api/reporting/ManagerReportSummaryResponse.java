package com.storagehub.api.reporting;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ManagerReportSummaryResponse(
    UUID facilityId,
    String facilityName,
    long totalUnits,
    long availableUnits,
    long occupiedUnits,
    long maintenanceUnits,
    long reservedUnits,
    double occupancyRate,
    BigDecimal monthlyRecurringRevenue,
    BigDecimal collectedRevenue,
    long overdueRentalsCount,
    long monthCheckinsCount,
    long openReturnsCount,
    long openMaintenanceTasksCount,
    List<StatusCount> unitStatusBreakdown,
    List<TypeCapacity> unitTypeBreakdown
) {
    public record StatusCount(
        String status,
        String name,
        long count,
        double percentage
    ) {}

    public record TypeCapacity(
        String typeCode,
        String typeName,
        long totalUnits,
        long occupiedUnits,
        double occupancyRate
    ) {}
}
