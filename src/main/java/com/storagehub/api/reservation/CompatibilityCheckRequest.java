package com.storagehub.api.reservation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class CompatibilityCheckRequest {

    @NotNull
    private UUID facilityId;

    @NotNull
    private UUID unitTypeId;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    @Size(max = 500)
    private String goodsCondition;

    @NotEmpty
    @Valid
    private List<GoodsItemRequest> goodsItems;

    public CompatibilityCheckRequest() {
    }

    public CompatibilityCheckRequest(UUID facilityId, UUID unitTypeId, LocalDate startDate,
                                     LocalDate endDate, String goodsCondition,
                                     List<GoodsItemRequest> goodsItems) {
        this.facilityId = facilityId;
        this.unitTypeId = unitTypeId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.goodsCondition = goodsCondition;
        this.goodsItems = goodsItems;
    }

    public UUID getFacilityId() { return facilityId; }
    public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
    public UUID getUnitTypeId() { return unitTypeId; }
    public void setUnitTypeId(UUID unitTypeId) { this.unitTypeId = unitTypeId; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public String getGoodsCondition() { return goodsCondition; }
    public void setGoodsCondition(String goodsCondition) { this.goodsCondition = goodsCondition; }
    public List<GoodsItemRequest> getGoodsItems() { return goodsItems; }
    public void setGoodsItems(List<GoodsItemRequest> goodsItems) { this.goodsItems = goodsItems; }
}
