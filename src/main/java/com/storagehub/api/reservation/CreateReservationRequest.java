package com.storagehub.api.reservation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

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

    private Instant appointmentAt;

    public CreateReservationRequest() {
    }

    public CreateReservationRequest(UUID quoteId, String goodsCondition, String notes,
                                    List<GoodsItemRequest> goodsItems) {
        this(quoteId, goodsCondition, notes, goodsItems, null);
    }

    public CreateReservationRequest(UUID quoteId, String goodsCondition, String notes,
                                    List<GoodsItemRequest> goodsItems, Instant appointmentAt) {
        this.quoteId = quoteId;
        this.goodsCondition = goodsCondition;
        this.notes = notes;
        this.goodsItems = goodsItems;
        this.appointmentAt = appointmentAt;
    }

    public UUID getQuoteId() { return quoteId; }
    public void setQuoteId(UUID quoteId) { this.quoteId = quoteId; }
    public String getGoodsCondition() { return goodsCondition; }
    public void setGoodsCondition(String goodsCondition) { this.goodsCondition = goodsCondition; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public List<GoodsItemRequest> getGoodsItems() { return goodsItems; }
    public void setGoodsItems(List<GoodsItemRequest> goodsItems) { this.goodsItems = goodsItems; }
    public Instant getAppointmentAt() { return appointmentAt; }
    public void setAppointmentAt(Instant appointmentAt) { this.appointmentAt = appointmentAt; }
}
