package com.storagehub.api.returns;

import com.storagehub.domain.model.CustomerDecision;
import com.storagehub.domain.model.DamageClassification;
import com.storagehub.domain.model.InventoryMatch;
import com.storagehub.domain.model.ReturnCaseStatus;
import com.storagehub.domain.model.ReturnType;
import com.storagehub.domain.model.SettlementMode;
import com.storagehub.domain.model.StorageUnitStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ReturnCaseResponse(
    UUID id,
    UUID rentalId,
    UUID facilityId,
    String facilityName,
    UUID storageUnitId,
    String unitNumber,
    UUID customerId,
    String customerName,
    String customerEmail,
    String customerPhone,
    ReturnCaseStatus status,
    ReturnType returnType,
    SettlementMode settlementMode,
    Instant requestedAt,
    LocalDate scheduledDate,
    String customerNotes,
    // Inspection
    UUID inspectedById,
    String inspectedByName,
    Instant inspectedAt,
    InventoryMatch inventoryMatch,
    DamageClassification damageClassification,
    String inspectionNotes,
    List<String> evidencePhotos,
    boolean returnedKey,
    boolean returnedCard,
    boolean returnedLock,
    StorageUnitStatus proposedUnitStatus,
    // Fees & settlement
    BigDecimal depositAmount,
    BigDecimal damageFee,
    BigDecimal cleaningFee,
    BigDecimal lostItemFee,
    BigDecimal overdueFee,
    BigDecimal outstandingFee,
    BigDecimal totalDeductions,
    BigDecimal netRefundAmount,
    BigDecimal amountDueFromCustomer,
    Integer overdueDays,
    // Customer response
    boolean customerConfirmed,
    Instant customerConfirmedAt,
    CustomerDecision customerDecision,
    String customerDecisionNote,
    // Manager review
    UUID reviewedById,
    String reviewedByName,
    Instant reviewedAt,
    String managerResolutionNote,
    // Finalization
    String settlementPaymentId,
    Instant settlementPaidAt,
    Instant completedAt,
    String sourceType,
    UUID recoveryTaskId,
    String recoveryTaskStatus,
    Instant recoveryCompletedAt,
    boolean setupExitReviewRequired,
    Instant moveInCompletedAt,
    SettlementReceipt settlementReceipt,
    OverdueSettlement overdueSettlement,
    String settlementBlocker,
    EarlyTerminationSummary earlyTerminationPolicy,
    ActiveReturnSummary activeReturnPolicy,
    boolean legacyPricingReviewRequired,
    boolean legacyPricingSnapshotAvailable,
    UUID legacyPricingEvidenceFileId
) {
    public record SettlementReceipt(
        String direction,
        BigDecimal amount,
        String paymentMethod,
        String transactionReference,
        UUID receiptFileId,
        Instant recordedAt
    ) {}
    public record OverdueSettlement(BigDecimal accruedAmount,BigDecimal settlementAmount,Long overdueDays,Instant calculatedAt,boolean requiresReconciliation) {}
    public record EarlyTerminationSummary(
        String type,
        BigDecimal earlyTerminationFee,
        int billableSetupDays,
        BigDecimal usedGoodsCharge,
        BigDecimal totalRentalCharge,
        BigDecimal rentalRefundAmount,
        int noticeDays
    ) {}

    public record ActiveReturnSummary(
        boolean recalculationRequired,
        int completedRentalMonths,
        Integer pricingTierMonths,
        int setupDaysUsed,
        int chargeableSetupDays,
        int rentalDaysUsed,
        int noticeShortfallDays,
        BigDecimal dailyPrice,
        BigDecimal earlyTerminationFee,
        BigDecimal noticeCharge,
        BigDecimal totalRentalCharge,
        BigDecimal rentalRefundAmount,
        int noticeDays
    ) {}
}
