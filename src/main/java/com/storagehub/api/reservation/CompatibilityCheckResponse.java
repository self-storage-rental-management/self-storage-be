package com.storagehub.api.reservation;

import com.storagehub.domain.model.CompatibilityResult;
import com.storagehub.domain.model.StorageUnit;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
    /** v8 tách trạng thái hình học khỏi trạng thái còn gian trống. */
    private String physicalFitStatus;
    private String availabilityStatus;
    private BigDecimal totalGoodsVolumeM3;
    private BigDecimal totalGoodsWeightKg;
    private BigDecimal unitVolumeM3;
    private BigDecimal unitMaxLoadKg;
    /** Giới hạn hình học và tải hàng của chính loại kho này. */
    private BigDecimal maxCargoLengthCm;
    private BigDecimal maxCargoWidthCm;
    private BigDecimal maxCargoHeightCm;
    private BigDecimal maxCargoWeightKg;
    private BigDecimal rackUtilizationRate;
    private BigDecimal usableVolumePerRackM3;
    private int requiredRackCount;
    private int unitRackCount;
    private long availableUnitCount;
    private Integer requiredPallets;
    private Integer availablePalletSlots;
    private boolean staffReviewRequired;
    private List<String> issues;
    private PalletPlan palletPlan;
    private boolean palletLayoutMissing;
    @JsonIgnore private StorageUnit palletHeldUnit;

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
    public String getPhysicalFitStatus() { return physicalFitStatus; }
    public void setPhysicalFitStatus(String physicalFitStatus) { this.physicalFitStatus = physicalFitStatus; }
    public String getAvailabilityStatus() { return availabilityStatus; }
    public void setAvailabilityStatus(String availabilityStatus) { this.availabilityStatus = availabilityStatus; }
    public BigDecimal getTotalGoodsVolumeM3() { return totalGoodsVolumeM3; }
    public BigDecimal getTotalGoodsWeightKg() { return totalGoodsWeightKg; }
    public BigDecimal getUnitVolumeM3() { return unitVolumeM3; }
    public BigDecimal getUnitMaxLoadKg() { return unitMaxLoadKg; }
    public BigDecimal getMaxCargoLengthCm() { return maxCargoLengthCm; }
    public void setMaxCargoLengthCm(BigDecimal maxCargoLengthCm) { this.maxCargoLengthCm = maxCargoLengthCm; }
    public BigDecimal getMaxCargoWidthCm() { return maxCargoWidthCm; }
    public void setMaxCargoWidthCm(BigDecimal maxCargoWidthCm) { this.maxCargoWidthCm = maxCargoWidthCm; }
    public BigDecimal getMaxCargoHeightCm() { return maxCargoHeightCm; }
    public void setMaxCargoHeightCm(BigDecimal maxCargoHeightCm) { this.maxCargoHeightCm = maxCargoHeightCm; }
    public BigDecimal getMaxCargoWeightKg() { return maxCargoWeightKg; }
    public void setMaxCargoWeightKg(BigDecimal maxCargoWeightKg) { this.maxCargoWeightKg = maxCargoWeightKg; }
    public BigDecimal getRackUtilizationRate() { return rackUtilizationRate; }
    public BigDecimal getUsableVolumePerRackM3() { return usableVolumePerRackM3; }
    public int getRequiredRackCount() { return requiredRackCount; }
    public int getUnitRackCount() { return unitRackCount; }
    public long getAvailableUnitCount() { return availableUnitCount; }
    public Integer getRequiredPallets() { return requiredPallets; }
    public void setRequiredPallets(Integer requiredPallets) { this.requiredPallets = requiredPallets; }
    public Integer getAvailablePalletSlots() { return availablePalletSlots; }
    public void setAvailablePalletSlots(Integer availablePalletSlots) { this.availablePalletSlots = availablePalletSlots; }
    public boolean isStaffReviewRequired() { return staffReviewRequired; }
    public List<String> getIssues() { return issues; }
    public PalletPlan getPalletPlan() { return palletPlan; }
    public void setPalletPlan(PalletPlan palletPlan) { this.palletPlan = palletPlan; }
    public boolean isPalletLayoutMissing() { return palletLayoutMissing; }
    public void setPalletLayoutMissing(boolean palletLayoutMissing) { this.palletLayoutMissing = palletLayoutMissing; }
    @JsonIgnore public StorageUnit getPalletHeldUnit() { return palletHeldUnit; }
    public void setPalletHeldUnit(StorageUnit palletHeldUnit) { this.palletHeldUnit = palletHeldUnit; }
}
