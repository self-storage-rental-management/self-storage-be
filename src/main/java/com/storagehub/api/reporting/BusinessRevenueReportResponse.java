package com.storagehub.api.reporting;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record BusinessRevenueReportResponse(
    BigDecimal totalRevenueCollected,
    BigDecimal totalRefunds,
    BigDecimal netRevenue,
    List<MonthlyRevenue> monthlyBreakdown,
    List<FacilityRevenue> facilityBreakdown,
    List<PurposeRevenue> paymentPurposeBreakdown
) {
    public record MonthlyRevenue(
        String month,
        BigDecimal revenue,
        long transactionsCount
    ) {}

    public record FacilityRevenue(
        UUID facilityId,
        String facilityName,
        BigDecimal revenue,
        double occupancyRate,
        long activeRentalsCount
    ) {}

    public record PurposeRevenue(
        String purpose,
        BigDecimal amount,
        long count
    ) {}
}
