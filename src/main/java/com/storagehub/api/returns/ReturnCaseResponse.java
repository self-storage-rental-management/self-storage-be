package com.storagehub.api.returns;

import com.storagehub.domain.model.CustomerDecision;
import com.storagehub.domain.model.DamageClassification;
import com.storagehub.domain.model.InventoryMatch;
import com.storagehub.domain.model.LockInspectionResult;
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
    UUID legacyPricingEvidenceFileId,
    boolean setupExitRequested,
    boolean setupEarlyExit,
    Instant setupReturnedAt,
    String setupEarlyExitPolicyVersion,
    BigDecimal rentalRefundCredit,
    BigDecimal earlyTerminationFee,
    Instant noShowAt,
    Instant recoveryEligibleAt,
    Instant recoveryTaskCreatedAt,
    UUID recoveryWitnessStaffId,
    String recoveryWitnessStaffName,
    Instant recoveryTaskDeadlineAt,
    Instant inventoryRemovalDeadlineAt,
    LockInspectionResult lockInspectionResult,
    Integer returnWindowDays,
    String returnPolicyVersion,
    Equipment equipment,
    Integer missingPalletCount,
    BigDecimal palletLossFee,
    Instant customerClearedAt,
    Boolean warehouseEmptyVerified,
    Instant warehouseEmptyVerifiedAt,
    UUID warehouseEmptyVerifiedBy,
    long clearanceRevision,
    String displayCode,
    String rentalDisplayCode,
    Attendance attendance,
    long settlementVersion,
    Instant settlementPublishedAt,
    Instant settlementPaymentDueAt,
    List<SettlementReceipt> settlementReceipts
) {
    public record Attendance(Instant arrivedAt, UUID evidenceId, Instant expiresAt, int attempts, Instant verifiedAt,
        Instant reviewStartsAt, Boolean reviewAccepted, Instant handoverDueAt, UUID handoverStaffId,
        Instant reverificationStartedAt, UUID reverificationStaffId, Instant handoverAppointmentAt) {}
    public record Equipment(int rackCount, int frameCount, Integer palletCount, BigDecimal palletUnitPrice) {}
    public boolean isManagerApprovalRequired() {
        return status == ReturnCaseStatus.awaiting_business_review && !legacyPricingReviewRequired
            && (returnType == ReturnType.INVENTORY_MISMATCH || returnType == ReturnType.RETURN_IN_SETUP || returnType == ReturnType.CANCELLED_IN_SETUP);
    }
    public Instant getManagerReviewDueAt() {
        return status == ReturnCaseStatus.awaiting_business_review && inspectedAt != null
            ? inspectedAt.plus(6, java.time.temporal.ChronoUnit.HOURS) : null;
    }
    public Instant getCustomerConfirmationDueAt() {
        Instant published = reviewedAt != null ? reviewedAt : inspectedAt;
        return status == ReturnCaseStatus.awaiting_customer_confirmation && published != null
            ? published.plus(72, java.time.temporal.ChronoUnit.HOURS) : null;
    }
    /** Existing readers can omit the new setup settlement projection fields. */
    public ReturnCaseResponse(
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
        Instant requestedAt,
        LocalDate scheduledDate,
        String customerNotes,
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
        boolean customerConfirmed,
        Instant customerConfirmedAt,
        CustomerDecision customerDecision,
        String customerDecisionNote,
        UUID reviewedById,
        String reviewedByName,
        Instant reviewedAt,
        String managerResolutionNote,
        String settlementPaymentId,
        Instant settlementPaidAt,
        Instant completedAt,
        String sourceType,
        UUID recoveryTaskId,
        String recoveryTaskStatus,
        Instant recoveryCompletedAt,
        SettlementReceipt settlementReceipt,
        OverdueSettlement overdueSettlement,
        String settlementBlocker
    ) {
        this(
            id, rentalId, facilityId, facilityName, storageUnitId, unitNumber, customerId, customerName,
            customerEmail, customerPhone, status, null, null, requestedAt, scheduledDate, customerNotes,
            inspectedById, inspectedByName, inspectedAt, inventoryMatch, damageClassification, inspectionNotes,
            evidencePhotos, returnedKey, returnedCard, returnedLock, proposedUnitStatus, depositAmount,
            damageFee, cleaningFee, lostItemFee, overdueFee, outstandingFee, totalDeductions, netRefundAmount,
            amountDueFromCustomer, overdueDays, customerConfirmed, customerConfirmedAt, customerDecision,
            customerDecisionNote, reviewedById, reviewedByName, reviewedAt, managerResolutionNote,
            settlementPaymentId, settlementPaidAt, completedAt, sourceType, recoveryTaskId, recoveryTaskStatus,
            recoveryCompletedAt, false, null, settlementReceipt, overdueSettlement, settlementBlocker,
            null, // earlyTerminationPolicy
            null, // activeReturnPolicy
            false, // legacyPricingReviewRequired
            false, // legacyPricingSnapshotAvailable
            null, // legacyPricingEvidenceFileId
            false, // setupExitRequested
            false, // setupEarlyExit
            null, // setupReturnedAt
            null, // setupEarlyExitPolicyVersion
            null, // rentalRefundCredit
            null, // earlyTerminationFee
            null, // noShowAt
            null, // recoveryEligibleAt
            null, // recoveryTaskCreatedAt
            null, // recoveryWitnessStaffId
            null, // recoveryWitnessStaffName
            null, // recoveryTaskDeadlineAt
            null, // inventoryRemovalDeadlineAt
            null, // lockInspectionResult
            null, // returnWindowDays
            null, // returnPolicyVersion
            null, // equipment
            null, // missingPalletCount
            null, // palletLossFee
            null, // customerClearedAt
            null, // warehouseEmptyVerified
            null, // warehouseEmptyVerifiedAt
            null, // warehouseEmptyVerifiedBy
            0L, // clearanceRevision
            null, // displayCode
            null, // rentalDisplayCode
            null, // attendance
            0L, // settlementVersion
            null, // settlementPublishedAt
            null, // settlementPaymentDueAt
            List.of() // settlementReceipts
        );
    }

    public record SettlementReceipt(
        String direction,
        BigDecimal amount,
        String paymentMethod,
        String transactionReference,
        UUID receiptFileId,
        Instant recordedAt,
        String receiptCode
    ) {
    public SettlementReceipt(String direction,
        BigDecimal amount,
        String paymentMethod,
        String transactionReference,
        UUID receiptFileId,
        Instant recordedAt) {
        this(direction, amount, paymentMethod, transactionReference, receiptFileId, recordedAt, null);
    }
}

    public record OverdueSettlement(
        BigDecimal accruedAmount,
        BigDecimal settlementAmount,
        Long overdueDays,
        Instant calculatedAt,
        boolean requiresReconciliation
    ) {}

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
        int noticeDays,
        Integer rentalMonths,
        BigDecimal monthlyPrice
    ) {}
}
