package com.storagehub.api.returns;

import com.storagehub.domain.model.CustomerDecision;
import com.storagehub.domain.model.DamageClassification;
import com.storagehub.domain.model.InventoryMatch;
import com.storagehub.domain.model.ReturnCaseStatus;
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
    Instant completedAt
) {}
