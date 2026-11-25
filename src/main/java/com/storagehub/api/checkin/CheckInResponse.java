package com.storagehub.api.checkin;

import com.storagehub.domain.model.CheckInStatus;
import com.storagehub.domain.model.ReservationStatus;
import com.storagehub.domain.model.StorageUnitStatus;
import com.storagehub.domain.model.UnitAssignmentStatus;
import com.storagehub.domain.model.UnitReleaseDisposition;
import com.storagehub.domain.model.InventoryResolutionStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CheckInResponse(
    UUID checkInId,
    CheckInStatus checkInStatus,
    Instant scheduledAt,
    Instant appointmentAt,
    Instant checkedInAt,
    Instant identityVerifiedAt,
    String verifiedIdentityDocument,
    String identityVerificationNote,
    Instant contractSignedAt,
    String contractEvidenceReference,
    Instant paymentCollectedAt,
    Instant setupStartedAt,
    Instant setupDueAt,
    Integer freeSetupDays,
    LocalDate paidRentalStartsOn,
    Instant setupCompletedAt,
    Instant inventoryVerifiedAt,
    Instant inventoryDueAt,
    boolean inventoryPending,
    String lockReference,
    String readinessNote,
    String rejectionReason,
    UnitReleaseDisposition rejectionDisposition,
    UUID performedBy,
    String performedByName,
    UUID assignedStaff,
    String assignedStaffName,
    UUID reservationId,
    String reservationCode,
    ReservationStatus reservationStatus,
    UUID customerId,
    String customerName,
    String customerEmail,
    String identityDocumentNumber,
    String customerPhone,
    UUID facilityId,
    String facilityName,
    UUID storageUnitId,
    String storageUnitCode,
    StorageUnitStatus storageUnitStatus,
    UUID assignmentId,
    UnitAssignmentStatus assignmentStatus,
    LocalDate startDate,
    LocalDate endDate,
    Integer declaredPackageCount,
    BigDecimal declaredGoodsWeightKg,
    BigDecimal declaredGoodsVolumeM3,
    BigDecimal declaredGoodsLengthCm,
    BigDecimal declaredGoodsWidthCm,
    BigDecimal declaredGoodsHeightCm,
    BigDecimal dueAtCheckIn,
    CompleteCheckInRequest handover,
    String setupEarlyExitPolicyVersion,
    BigDecimal setupEarlyExitFee,
    List<DeclaredGoodsLine> declaredGoodsLines,
    IntakeAction intakeAction,
    Instant inventoryFinalDeadlineAt,
    InventoryResolutionStatus inventoryResolutionStatus,
    String inventoryRefusalStatus,
    boolean inventoryFullFit,
    boolean inventoryPartialFitAvailable,
    Integer inventorySuggestedPackageCount,
    String inventoryMismatchSummary,
    String inventoryAcceptedQuantitiesJson,
    Instant inventoryRemainderDeadlineAt,
    Instant inventoryRemainderRemovedAt,
    String inventoryRemainderRemovalNote,
    String inventoryActualJson,
    String inventoryMismatchJson,
    String inventorySuggestedQuantitiesJson,
    long inventoryResultVersion,
    java.util.UUID inventoryAtSiteConfirmedBy,
    String inventoryAtSiteConfirmedByName,
    Instant inventoryDecisionAt,
    boolean inventoryAwaitingOnlineConfirmation,
    Instant receiptConfirmationDueAt,
    boolean receiptConfirmationOverdue,
    Instant receiptConfirmationReleaseAt,
    boolean receiptReleaseEligible,
    String declaredPackagingMethod,
    String paymentEvidenceReference,
    InventoryVisit inventoryVisit,
    Instant checkInDeadline,
    StorageCapacity storageCapacity,
    InventoryDecisionResponse inventoryDecision,
    HandoverRecord handoverRecord,
    Instant customerReceiptConfirmedAt,
    String facilityAddress,
    Integer inventoryReturnWindowDays,
    Instant inventoryRemovalPublishedAt,
    Instant inventoryRemovalDeadlineAt,
    Instant inventoryReturnLastReminderAt,
    Equipment equipment,
    Attendance attendance,
    String declaredIdentityMasked,
    boolean identityCorrected
) {
    public record Attendance(Instant deadline, Instant arrivedAt, String termsVersion, Instant completionDeadline, boolean arrivalUnderReview) {}

    public record Equipment(int rackCount, int frameCount, Integer palletCount) {}

    public record InventoryVisit(
        Instant appointmentAt, Instant arrivedAt, Instant identityVerifiedAt,
        String verifiedByName, UUID photoId
    ) {}

    public record StorageCapacity(
        BigDecimal volumeM3,
        BigDecimal maxLoadKg,
        BigDecimal maxCargoLengthCm,
        BigDecimal maxCargoWidthCm,
        BigDecimal maxCargoHeightCm,
        BigDecimal maxCargoWeightKg
    ) {}

    public record DeclaredGoodsLine(UUID id, String code, String name, String categoryName,
        String materialName, int quantity, BigDecimal lengthCm, BigDecimal widthCm,
        BigDecimal heightCm, BigDecimal weightPerPackageKg, UUID catalogCategoryId, UUID catalogMaterialId,
        com.storagehub.domain.model.GoodsCategory category, boolean fragile, boolean stackable,
        boolean allowHorizontalPlacement, int maxStackLayers) {
        public DeclaredGoodsLine(UUID id, String code, String name, String categoryName, String materialName, int quantity,
            BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, BigDecimal weightPerPackageKg) {
            this(id, code, name, categoryName, materialName, quantity, lengthCm, widthCm, heightCm, weightPerPackageKg,
                null, null, null, false, false, false, 1);
        }
        /** Compatibility constructor for readers that only populated the original measurements. */
        public DeclaredGoodsLine(UUID id, String code, String name, int quantity,
            BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, BigDecimal weightPerPackageKg) {
            this(id, code, name, null, null, quantity, lengthCm, widthCm, heightCm, weightPerPackageKg);
        }
    }
    public enum IntakeAction { START, CONTINUE, NONE }
    /** Existing readers can omit the new setup settlement projection fields. */
    public CheckInResponse(
        UUID checkInId,
        CheckInStatus checkInStatus,
        Instant scheduledAt,
        Instant appointmentAt,
        Instant checkedInAt,
        Instant identityVerifiedAt,
        String verifiedIdentityDocument,
        String identityVerificationNote,
        Instant contractSignedAt,
        String contractEvidenceReference,
        Instant paymentCollectedAt,
        Instant setupStartedAt,
        Instant setupDueAt,
        Integer freeSetupDays,
        LocalDate paidRentalStartsOn,
        Instant setupCompletedAt,
        Instant inventoryVerifiedAt,
        Instant inventoryDueAt,
        boolean inventoryPending,
        String lockReference,
        String readinessNote,
        String rejectionReason,
        UnitReleaseDisposition rejectionDisposition,
        UUID performedBy,
        String performedByName,
        UUID reservationId,
        String reservationCode,
        ReservationStatus reservationStatus,
        UUID customerId,
        String customerName,
        String customerEmail,
        String identityDocumentNumber,
        String customerPhone,
        UUID facilityId,
        String facilityName,
        UUID storageUnitId,
        String storageUnitCode,
        StorageUnitStatus storageUnitStatus,
        UUID assignmentId,
        UnitAssignmentStatus assignmentStatus,
        LocalDate startDate,
        LocalDate endDate,
        Integer declaredPackageCount,
        BigDecimal declaredGoodsWeightKg,
        BigDecimal declaredGoodsVolumeM3,
        BigDecimal declaredGoodsLengthCm,
        BigDecimal declaredGoodsWidthCm,
        BigDecimal declaredGoodsHeightCm,
        BigDecimal dueAtCheckIn,
        CompleteCheckInRequest handover
    ) {
        this(
            checkInId, checkInStatus, scheduledAt, appointmentAt, checkedInAt,
            identityVerifiedAt, verifiedIdentityDocument, identityVerificationNote,
            contractSignedAt, contractEvidenceReference, paymentCollectedAt,
            setupStartedAt, setupDueAt, freeSetupDays, paidRentalStartsOn,
            setupCompletedAt, inventoryVerifiedAt, inventoryDueAt, inventoryPending,
            lockReference, readinessNote, rejectionReason, rejectionDisposition,
            performedBy, performedByName, null, null, reservationId, reservationCode,
            reservationStatus, customerId, customerName, customerEmail,
            identityDocumentNumber, customerPhone, facilityId, facilityName,
            storageUnitId, storageUnitCode, storageUnitStatus, assignmentId,
            assignmentStatus, startDate, endDate, declaredPackageCount,
            declaredGoodsWeightKg, declaredGoodsVolumeM3, declaredGoodsLengthCm,
            declaredGoodsWidthCm, declaredGoodsHeightCm, dueAtCheckIn, handover,
            null, null, List.of(), IntakeAction.NONE,
            null, null, null, false, false,
            null, null, null, null, null, null, null, null,
            null, 0L, null, null, null, false,
            null, false, null, false,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, false
        );
    }

}
