package com.storagehub.api.reservation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class ReservationRecommendationRequest {

    @NotNull
    private UUID facilityId;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    @jakarta.validation.constraints.NotBlank
    @Size(max = 30)
    private String pricingPackageCode;

    @jakarta.validation.constraints.NotBlank
    @Size(max = 500)
    private String goodsCondition;

    @NotEmpty
    @Valid
    private List<@NotNull @Valid GoodsItemRequest> goodsItems;

    public ReservationRecommendationRequest() {
    }

    public UUID getFacilityId() { return facilityId; }
    public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public String getPricingPackageCode() { return pricingPackageCode; }
    public void setPricingPackageCode(String pricingPackageCode) { this.pricingPackageCode = pricingPackageCode; }
    public String getGoodsCondition() { return goodsCondition; }
    public void setGoodsCondition(String goodsCondition) { this.goodsCondition = goodsCondition; }
    public List<GoodsItemRequest> getGoodsItems() { return goodsItems; }
    public void setGoodsItems(List<GoodsItemRequest> goodsItems) { this.goodsItems = goodsItems; }
}
