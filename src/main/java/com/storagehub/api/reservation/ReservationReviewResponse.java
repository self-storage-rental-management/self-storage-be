package com.storagehub.api.reservation;

import com.storagehub.domain.model.GoodsReviewStatus;
import com.storagehub.domain.model.ReservationStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class ReservationReviewResponse {

    private UUID reservationId;
    private String reservationCode;
    private UUID facilityId;
    private UUID unitTypeId;
    private UUID customerId;
    private String customerEmail;
    private ReservationStatus reservationStatus;
    private GoodsReviewStatus goodsReviewStatus;
    private LocalDate startDate;
    private LocalDate endDate;
    private String goodsCondition;
    private Instant reviewDueAt;
    private Instant reviewedAt;
    private String reviewNote;
    private List<ReservationGoodsItemResponse> goodsItems;

    public ReservationReviewResponse() {
    }

    public UUID getReservationId() { return reservationId; }
    public void setReservationId(UUID reservationId) { this.reservationId = reservationId; }
    public String getReservationCode() { return reservationCode; }
    public void setReservationCode(String reservationCode) { this.reservationCode = reservationCode; }
    public UUID getFacilityId() { return facilityId; }
    public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
    public UUID getUnitTypeId() { return unitTypeId; }
    public void setUnitTypeId(UUID unitTypeId) { this.unitTypeId = unitTypeId; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }
    public ReservationStatus getReservationStatus() { return reservationStatus; }
    public void setReservationStatus(ReservationStatus reservationStatus) { this.reservationStatus = reservationStatus; }
    public GoodsReviewStatus getGoodsReviewStatus() { return goodsReviewStatus; }
    public void setGoodsReviewStatus(GoodsReviewStatus goodsReviewStatus) { this.goodsReviewStatus = goodsReviewStatus; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public String getGoodsCondition() { return goodsCondition; }
    public void setGoodsCondition(String goodsCondition) { this.goodsCondition = goodsCondition; }
    public Instant getReviewDueAt() { return reviewDueAt; }
    public void setReviewDueAt(Instant reviewDueAt) { this.reviewDueAt = reviewDueAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public List<ReservationGoodsItemResponse> getGoodsItems() { return goodsItems; }
    public void setGoodsItems(List<ReservationGoodsItemResponse> goodsItems) { this.goodsItems = goodsItems; }
}
