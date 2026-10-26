package com.storagehub.api.reservation;

import java.time.Instant;
import java.util.List;
import com.storagehub.api.payment.CustomerPaymentReceiptResponse;
import com.storagehub.domain.model.ReservationCancellationType;
import java.math.BigDecimal;

public class ReservationDetailResponse {

    private ReservationResponse reservation;
    private String goodsCondition;
    private String notes;
    private Instant paymentExpiresAt;
    private Instant complaintExpiresAt;
    private Instant archivedAt;
    private Instant confirmedAt;
    private Instant cancelledAt;
    private String cancelReason;
    private ReservationCancellationType cancellationType;
    private BigDecimal cancellationRefundAmount;
    private BigDecimal cancellationChargeAmount;
    private List<ReservationGoodsItemResponse> goodsItems;
    private List<CustomerPaymentReceiptResponse> payments;

    public ReservationDetailResponse() {
    }

    public ReservationResponse getReservation() { return reservation; }
    public void setReservation(ReservationResponse reservation) { this.reservation = reservation; }
    public String getGoodsCondition() { return goodsCondition; }
    public void setGoodsCondition(String goodsCondition) { this.goodsCondition = goodsCondition; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getPaymentExpiresAt() { return paymentExpiresAt; }
    public void setPaymentExpiresAt(Instant paymentExpiresAt) { this.paymentExpiresAt = paymentExpiresAt; }
    public Instant getComplaintExpiresAt() { return complaintExpiresAt; }
    public void setComplaintExpiresAt(Instant complaintExpiresAt) { this.complaintExpiresAt = complaintExpiresAt; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public ReservationCancellationType getCancellationType() { return cancellationType; }
    public void setCancellationType(ReservationCancellationType cancellationType) { this.cancellationType = cancellationType; }
    public BigDecimal getCancellationRefundAmount() { return cancellationRefundAmount; }
    public void setCancellationRefundAmount(BigDecimal cancellationRefundAmount) { this.cancellationRefundAmount = cancellationRefundAmount; }
    public BigDecimal getCancellationChargeAmount() { return cancellationChargeAmount; }
    public void setCancellationChargeAmount(BigDecimal cancellationChargeAmount) { this.cancellationChargeAmount = cancellationChargeAmount; }
    public List<ReservationGoodsItemResponse> getGoodsItems() { return goodsItems; }
    public void setGoodsItems(List<ReservationGoodsItemResponse> goodsItems) { this.goodsItems = goodsItems; }
    public List<CustomerPaymentReceiptResponse> getPayments() { return payments; }
    public void setPayments(List<CustomerPaymentReceiptResponse> payments) { this.payments = payments; }
}
