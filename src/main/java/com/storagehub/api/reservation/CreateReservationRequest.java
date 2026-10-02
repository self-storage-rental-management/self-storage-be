package com.storagehub.api.reservation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public class CreateReservationRequest {

    @NotNull
    private UUID quoteId;

    @Size(max = 500)
    private String goodsCondition;

    @Size(max = 2000)
    private String notes;

    @NotEmpty
    @Valid
    private List<GoodsItemRequest> goodsItems;

    public CreateReservationRequest() {
    }

    public CreateReservationRequest(UUID quoteId, String goodsCondition, String notes,
                                    List<GoodsItemRequest> goodsItems) {
        this.quoteId = quoteId;
        this.goodsCondition = goodsCondition;
        this.notes = notes;
        this.goodsItems = goodsItems;
    }

    public UUID getQuoteId() { return quoteId; }
    public void setQuoteId(UUID quoteId) { this.quoteId = quoteId; }
    public String getGoodsCondition() { return goodsCondition; }
    public void setGoodsCondition(String goodsCondition) { this.goodsCondition = goodsCondition; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public List<GoodsItemRequest> getGoodsItems() { return goodsItems; }
    public void setGoodsItems(List<GoodsItemRequest> goodsItems) { this.goodsItems = goodsItems; }
}
