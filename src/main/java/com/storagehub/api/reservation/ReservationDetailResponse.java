package com.storagehub.api.reservation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import com.storagehub.api.payment.CustomerPaymentReceiptResponse;
import com.storagehub.domain.model.ReservationCancellationType;
import java.math.BigDecimal;
import com.storagehub.domain.model.InventoryResolutionStatus;

public class ReservationDetailResponse {

    private com.storagehub.api.checkin.InventoryDecisionResponse inventoryDecision;
    public com.storagehub.api.checkin.InventoryDecisionResponse getInventoryDecision() { return inventoryDecision; }
    public void setInventoryDecision(com.storagehub.api.checkin.InventoryDecisionResponse value) { inventoryDecision = value; }

    private ReservationResponse reservation;
    private CurrentFacility currentFacility;
    private CurrentUnitType currentUnitType;
    private String goodsCondition;
    private String notes;
    private Instant paymentExpiresAt;
    private Instant paymentStartedAt;
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
    private String checkInStatus;
    private Instant handoverCompletedAt;
    private String lockReference;
    private Instant receiptConfirmationDueAt;
    private boolean receiptConfirmationOverdue;
    private Instant receiptConfirmationReleaseAt;
    private boolean receiptReleaseEligible;
    private Instant setupStartedAt;
    private Instant setupDueAt;
    private Integer freeSetupDays;
    private LocalDate paidRentalStartsOn;
    private String rentalStatus;
    private Instant inventoryDueAt;
    private Instant inventoryFinalDeadlineAt;
    private Instant inventoryResultPublishedAt;
    private Instant inventoryRemainderDeadlineAt;
    private Instant inventoryRemovalPublishedAt;
    private Instant inventoryRemovalDeadlineAt;
    private Integer inventoryReturnWindowDays;
    private Integer inventoryReturnExtensionDays;
    private Integer inventoryReturnExtensionCount;
    private boolean inventoryReturnCanExtend;
    private String inventoryReturnPolicyVersion;
    private Instant inventoryEscalatedAt;
    private Instant setupCompletedAt;
    private Instant inventoryAppointmentAt;
    private Instant inventoryArrivedAt;
    private Instant inventoryIdentityVerifiedAt;
    public Instant getInventoryRemovalPublishedAt() { return inventoryRemovalPublishedAt; }
    public void setInventoryRemovalPublishedAt(Instant value) { inventoryRemovalPublishedAt = value; }
    private InventoryResolutionStatus inventoryResolutionStatus;
    private boolean inventoryFullFit;
    private boolean inventoryPartialFitAvailable;
    private Integer inventorySuggestedPackageCount;
    private String inventoryMismatchSummary;
    private String inventoryAcceptedQuantitiesJson;
    private String inventoryActualJson;
    private String inventoryMismatchJson;
    private String inventorySuggestedQuantitiesJson;
    private long inventoryResultVersion;
    private String inventoryAtSiteConfirmedByName;
    private java.time.Instant inventoryDecisionAt;

    public record CurrentFacility(String name, String address, String city, String phone, String accessHours) {}
    public record CurrentUnitType(String name, BigDecimal lengthM, BigDecimal widthM, BigDecimal heightM,
        BigDecimal volumeM3, BigDecimal maxLoadKg, Integer rackCount, BigDecimal rackLengthM,
        BigDecimal rackWidthM, BigDecimal rackHeightM) {}

    public ReservationDetailResponse() {
    }

    public ReservationResponse getReservation() { return reservation; }
    public void setReservation(ReservationResponse reservation) { this.reservation = reservation; }
    public CurrentFacility getCurrentFacility() { return currentFacility; }
    public void setCurrentFacility(CurrentFacility value) { currentFacility = value; }
    public CurrentUnitType getCurrentUnitType() { return currentUnitType; }
    public void setCurrentUnitType(CurrentUnitType value) { currentUnitType = value; }
    public String getGoodsCondition() { return goodsCondition; }
    public void setGoodsCondition(String goodsCondition) { this.goodsCondition = goodsCondition; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getPaymentExpiresAt() { return paymentExpiresAt; }
    public void setPaymentExpiresAt(Instant paymentExpiresAt) { this.paymentExpiresAt = paymentExpiresAt; }
    public Instant getPaymentStartedAt() { return paymentStartedAt; }
    public void setPaymentStartedAt(Instant paymentStartedAt) { this.paymentStartedAt = paymentStartedAt; }
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
    public String getCheckInStatus() { return checkInStatus; }
    public void setCheckInStatus(String checkInStatus) { this.checkInStatus = checkInStatus; }
    public Instant getHandoverCompletedAt() { return handoverCompletedAt; }
    public void setHandoverCompletedAt(Instant value) { handoverCompletedAt = value; }
    public String getLockReference() { return lockReference; }
    public void setLockReference(String value) { lockReference = value; }
    public Instant getReceiptConfirmationDueAt() { return receiptConfirmationDueAt; }
    public void setReceiptConfirmationDueAt(Instant value) { receiptConfirmationDueAt = value; }
    public boolean isReceiptConfirmationOverdue() { return receiptConfirmationOverdue; }
    public void setReceiptConfirmationOverdue(boolean value) { receiptConfirmationOverdue = value; }
    public Instant getReceiptConfirmationReleaseAt() { return receiptConfirmationReleaseAt; }
    public void setReceiptConfirmationReleaseAt(Instant value) { receiptConfirmationReleaseAt = value; }
    public boolean isReceiptReleaseEligible() { return receiptReleaseEligible; }
    public void setReceiptReleaseEligible(boolean value) { receiptReleaseEligible = value; }
    public Instant getSetupStartedAt() { return setupStartedAt; }
    public void setSetupStartedAt(Instant value) { setupStartedAt = value; }
    public Instant getSetupDueAt() { return setupDueAt; }
    public void setSetupDueAt(Instant value) { setupDueAt = value; }
    public Integer getFreeSetupDays() { return freeSetupDays; }
    public void setFreeSetupDays(Integer value) { freeSetupDays = value; }
    public LocalDate getPaidRentalStartsOn() { return paidRentalStartsOn; }
    public void setPaidRentalStartsOn(LocalDate value) { paidRentalStartsOn = value; }
    public String getRentalStatus() { return rentalStatus; }
    public void setRentalStatus(String rentalStatus) { this.rentalStatus = rentalStatus; }
    public Instant getInventoryDueAt() { return inventoryDueAt; }
    public void setInventoryDueAt(Instant value) { inventoryDueAt = value; }
    public Instant getInventoryFinalDeadlineAt() { return inventoryFinalDeadlineAt; }
    public void setInventoryFinalDeadlineAt(Instant value) { inventoryFinalDeadlineAt = value; }
    public Instant getInventoryRemovalDeadlineAt() { return inventoryRemovalDeadlineAt; }
    public void setInventoryRemovalDeadlineAt(Instant value) { inventoryRemovalDeadlineAt = value; }
    public Integer getInventoryReturnWindowDays() { return inventoryReturnWindowDays; }
    public void setInventoryReturnWindowDays(Integer value) { inventoryReturnWindowDays = value; }
    public Integer getInventoryReturnExtensionDays() { return inventoryReturnExtensionDays; }
    public void setInventoryReturnExtensionDays(Integer value) { inventoryReturnExtensionDays = value; }
    public Integer getInventoryReturnExtensionCount() { return inventoryReturnExtensionCount; }
    public void setInventoryReturnExtensionCount(Integer value) { inventoryReturnExtensionCount = value; }
    public boolean isInventoryReturnCanExtend() { return inventoryReturnCanExtend; }
    public void setInventoryReturnCanExtend(boolean value) { inventoryReturnCanExtend = value; }
    public String getInventoryReturnPolicyVersion() { return inventoryReturnPolicyVersion; }
    public void setInventoryReturnPolicyVersion(String value) { inventoryReturnPolicyVersion = value; }
    public Instant getInventoryResultPublishedAt() { return inventoryResultPublishedAt; }
    public void setInventoryResultPublishedAt(Instant value) { inventoryResultPublishedAt = value; }
    public Instant getInventoryRemainderDeadlineAt() { return inventoryRemainderDeadlineAt; }
    public void setInventoryRemainderDeadlineAt(Instant value) { inventoryRemainderDeadlineAt = value; }
    public Instant getInventoryEscalatedAt() { return inventoryEscalatedAt; }
    public void setInventoryEscalatedAt(Instant value) { inventoryEscalatedAt = value; }
    public Instant getSetupCompletedAt() { return setupCompletedAt; }
    public void setSetupCompletedAt(Instant value) { setupCompletedAt = value; }
    public Instant getInventoryAppointmentAt() { return inventoryAppointmentAt; }
    public void setInventoryAppointmentAt(Instant value) { inventoryAppointmentAt = value; }
    public Instant getInventoryArrivedAt() { return inventoryArrivedAt; }
    public void setInventoryArrivedAt(Instant value) { inventoryArrivedAt = value; }
    public Instant getInventoryIdentityVerifiedAt() { return inventoryIdentityVerifiedAt; }
    public void setInventoryIdentityVerifiedAt(Instant value) { inventoryIdentityVerifiedAt = value; }
    public InventoryResolutionStatus getInventoryResolutionStatus() { return inventoryResolutionStatus; }
    public void setInventoryResolutionStatus(InventoryResolutionStatus value) { inventoryResolutionStatus = value; }
    public boolean isInventoryFullFit() { return inventoryFullFit; }
    public void setInventoryFullFit(boolean value) { inventoryFullFit = value; }
    public boolean isInventoryPartialFitAvailable() { return inventoryPartialFitAvailable; }
    public void setInventoryPartialFitAvailable(boolean value) { inventoryPartialFitAvailable = value; }
    public Integer getInventorySuggestedPackageCount() { return inventorySuggestedPackageCount; }
    public void setInventorySuggestedPackageCount(Integer value) { inventorySuggestedPackageCount = value; }
    public String getInventoryMismatchSummary() { return inventoryMismatchSummary; }
    public void setInventoryMismatchSummary(String value) { inventoryMismatchSummary = value; }
    public String getInventoryAcceptedQuantitiesJson() { return inventoryAcceptedQuantitiesJson; }
    public void setInventoryAcceptedQuantitiesJson(String value) { inventoryAcceptedQuantitiesJson = value; }
    public String getInventoryActualJson() { return inventoryActualJson; }
    public void setInventoryActualJson(String value) { inventoryActualJson = value; }
    public String getInventoryMismatchJson() { return inventoryMismatchJson; }
    public void setInventoryMismatchJson(String value) { inventoryMismatchJson = value; }
    public String getInventorySuggestedQuantitiesJson() { return inventorySuggestedQuantitiesJson; }
    public void setInventorySuggestedQuantitiesJson(String value) { inventorySuggestedQuantitiesJson = value; }
    public long getInventoryResultVersion() { return inventoryResultVersion; }
    public void setInventoryResultVersion(long value) { inventoryResultVersion = value; }
    public String getInventoryAtSiteConfirmedByName() { return inventoryAtSiteConfirmedByName; }
    public void setInventoryAtSiteConfirmedByName(String value) { inventoryAtSiteConfirmedByName = value; }
    public java.time.Instant getInventoryDecisionAt() { return inventoryDecisionAt; }
    public void setInventoryDecisionAt(java.time.Instant value) { inventoryDecisionAt = value; }
}
