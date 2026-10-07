package com.storagehub.api.reservation;

import com.storagehub.domain.model.CompatibilityResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class CompatibilityCheckResponse {

    private UUID facilityId;
    private UUID unitTypeId;
    private LocalDate startDate;
    private LocalDate endDate;
    private CompatibilityResult result;
    private BigDecimal totalGoodsVolumeM3;
    private BigDecimal totalGoodsWeightKg;
    private BigDecimal unitVolumeM3;
    private BigDecimal unitMaxLoadKg;
    private BigDecimal rackUtilizationRate;
    private BigDecimal usableVolumePerRackM3;
    private int requiredRackCount;
    private int unitRackCount;
    private long availableUnitCount;
    private boolean staffReviewRequired;
    private List<String> issues;

    public CompatibilityCheckResponse() {
    }

    public CompatibilityCheckResponse(UUID facilityId, UUID unitTypeId, LocalDate startDate,
                                      LocalDate endDate, CompatibilityResult result,
                                      BigDecimal totalGoodsVolumeM3, BigDecimal totalGoodsWeightKg,
                                      BigDecimal unitVolumeM3, BigDecimal unitMaxLoadKg,
                                      BigDecimal rackUtilizationRate, BigDecimal usableVolumePerRackM3,
                                      int requiredRackCount, int unitRackCount,
                                      long availableUnitCount, boolean staffReviewRequired,
                                      List<String> issues) {
        this.facilityId = facilityId;
        this.unitTypeId = unitTypeId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.result = result;
        this.totalGoodsVolumeM3 = totalGoodsVolumeM3;
        this.totalGoodsWeightKg = totalGoodsWeightKg;
        this.unitVolumeM3 = unitVolumeM3;
        this.unitMaxLoadKg = unitMaxLoadKg;
        this.rackUtilizationRate = rackUtilizationRate;
        this.usableVolumePerRackM3 = usableVolumePerRackM3;
        this.requiredRackCount = requiredRackCount;
        this.unitRackCount = unitRackCount;
        this.availableUnitCount = availableUnitCount;
        this.staffReviewRequired = staffReviewRequired;
        this.issues = issues;
    }

    public UUID getFacilityId() { return facilityId; }
    public UUID getUnitTypeId() { return unitTypeId; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public CompatibilityResult getResult() { return result; }
    public BigDecimal getTotalGoodsVolumeM3() { return totalGoodsVolumeM3; }
    public BigDecimal getTotalGoodsWeightKg() { return totalGoodsWeightKg; }
    public BigDecimal getUnitVolumeM3() { return unitVolumeM3; }
    public BigDecimal getUnitMaxLoadKg() { return unitMaxLoadKg; }
    public BigDecimal getRackUtilizationRate() { return rackUtilizationRate; }
    public BigDecimal getUsableVolumePerRackM3() { return usableVolumePerRackM3; }
    public int getRequiredRackCount() { return requiredRackCount; }
    public int getUnitRackCount() { return unitRackCount; }
    public long getAvailableUnitCount() { return availableUnitCount; }
    public boolean isStaffReviewRequired() { return staffReviewRequired; }
    public List<String> getIssues() { return issues; }
}
