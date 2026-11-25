package com.storagehub.api.returns;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.storagehub.domain.model.DamageClassification;
import com.storagehub.domain.model.InventoryMatch;
import com.storagehub.domain.model.LockInspectionResult;
import com.storagehub.domain.model.StorageUnitStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ReturnInspectionRequest(
    @NotNull(message = "inventoryMatch is required")
    InventoryMatch inventoryMatch,

    @NotNull(message = "damageClassification is required")
    DamageClassification damageClassification,

    @NotNull(message = "damageFee is required")
    @DecimalMin(value = "0.00", message = "damageFee must be >= 0")
    BigDecimal damageFee,

    @NotNull(message = "cleaningFee is required")
    @DecimalMin(value = "0.00", message = "cleaningFee must be >= 0")
    BigDecimal cleaningFee,

    @NotNull(message = "lostItemFee is required")
    @DecimalMin(value = "0.00", message = "lostItemFee must be >= 0")
    BigDecimal lostItemFee,

    @NotNull(message = "overdueFee is required")
    @DecimalMin(value = "0.00", message = "overdueFee must be >= 0")
    BigDecimal overdueFee,

    @NotNull(message = "outstandingFee is required")
    @DecimalMin(value = "0.00", message = "outstandingFee must be >= 0")
    BigDecimal outstandingFee,

    @Size(max = 4000, message = "staffNotes cannot exceed 4000 characters")
    String staffNotes,

    List<String> evidencePhotos,

    boolean returnedKey,
    boolean returnedCard,
    boolean returnedLock,

    StorageUnitStatus proposedUnitStatus,
    boolean unitVacated,

    List<DamageItem> damageItems,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    LockInspectionResult lockInspectionResult,
    Integer missingPalletCount
) {
    public record DamageItem(String code, BigDecimal quantity) {}

    @JsonCreator
    public ReturnInspectionRequest(
        @JsonProperty("inventoryMatch") InventoryMatch inventoryMatch,
        @JsonProperty("damageClassification") DamageClassification damageClassification,
        @JsonProperty("damageFee") BigDecimal damageFee,
        @JsonProperty("cleaningFee") BigDecimal cleaningFee,
        @JsonProperty("lostItemFee") BigDecimal lostItemFee,
        @JsonProperty("overdueFee") BigDecimal overdueFee,
        @JsonProperty("outstandingFee") BigDecimal outstandingFee,
        @JsonProperty("staffNotes") String staffNotes,
        @JsonProperty("evidencePhotos") List<String> evidencePhotos,
        @JsonProperty("returnedKey") boolean returnedKey,
        @JsonProperty("returnedCard") boolean returnedCard,
        @JsonProperty("returnedLock") boolean returnedLock,
        @JsonProperty("proposedUnitStatus") StorageUnitStatus proposedUnitStatus,
        @JsonProperty("unitVacated") Boolean unitVacated,
        @JsonProperty("recoveryHandoverLocation") String recoveryHandoverLocation,
        @JsonProperty("customerPresent") Boolean customerPresent,
        @JsonProperty("recoveryWitnessStaffId") UUID recoveryWitnessStaffId,
        @JsonProperty("damageItems") List<DamageItem> damageItems,
        @JsonProperty("lockInspectionResult") LockInspectionResult lockInspectionResult,
        @JsonProperty("missingPalletCount") Integer missingPalletCount
    ) {
        this(inventoryMatch, damageClassification, damageFee, cleaningFee, lostItemFee, overdueFee,
            outstandingFee, staffNotes, evidencePhotos, returnedKey, returnedCard, returnedLock,
            proposedUnitStatus, Boolean.TRUE.equals(unitVacated), recoveryHandoverLocation,
            customerPresent == null || customerPresent, recoveryWitnessStaffId, damageItems, lockInspectionResult, missingPalletCount);
    }

    public ReturnInspectionRequest(InventoryMatch inventoryMatch, DamageClassification damageClassification,
        BigDecimal damageFee, BigDecimal cleaningFee, BigDecimal lostItemFee, BigDecimal overdueFee,
        BigDecimal outstandingFee, String staffNotes, List<String> evidencePhotos, boolean returnedKey,
        boolean returnedCard, boolean returnedLock, StorageUnitStatus proposedUnitStatus, boolean unitVacated,
        String recoveryHandoverLocation, boolean customerPresent, UUID recoveryWitnessStaffId, List<DamageItem> damageItems,
        LockInspectionResult lockInspectionResult) {
        this(inventoryMatch, damageClassification, damageFee, cleaningFee, lostItemFee, overdueFee,
            outstandingFee, staffNotes, evidencePhotos, returnedKey, returnedCard, returnedLock, proposedUnitStatus,
            unitVacated, recoveryHandoverLocation, customerPresent, recoveryWitnessStaffId, damageItems, lockInspectionResult, null);
    }

    public ReturnInspectionRequest(InventoryMatch inventoryMatch, DamageClassification damageClassification,
        BigDecimal damageFee, BigDecimal cleaningFee, BigDecimal lostItemFee, BigDecimal overdueFee,
        BigDecimal outstandingFee, String staffNotes, List<String> evidencePhotos, boolean returnedKey,
        boolean returnedCard, boolean returnedLock, StorageUnitStatus proposedUnitStatus, boolean unitVacated,
        String recoveryHandoverLocation, boolean customerPresent, UUID recoveryWitnessStaffId, List<DamageItem> damageItems) {
        this(inventoryMatch, damageClassification, damageFee, cleaningFee, lostItemFee, overdueFee,
            outstandingFee, staffNotes, evidencePhotos, returnedKey, returnedCard, returnedLock, proposedUnitStatus,
            unitVacated, recoveryHandoverLocation, customerPresent, recoveryWitnessStaffId, damageItems, null, null);
    }

    public ReturnInspectionRequest(InventoryMatch inventoryMatch, DamageClassification damageClassification,
        BigDecimal damageFee, BigDecimal cleaningFee, BigDecimal lostItemFee, BigDecimal overdueFee,
        BigDecimal outstandingFee, String staffNotes, List<String> evidencePhotos, boolean returnedKey,
        boolean returnedCard, boolean returnedLock, StorageUnitStatus proposedUnitStatus) {
        this(inventoryMatch, damageClassification, damageFee, cleaningFee, lostItemFee, overdueFee,
            outstandingFee, staffNotes, evidencePhotos, returnedKey, returnedCard, returnedLock, proposedUnitStatus,
            false, null, true, null, null, null, null);
    }

    public ReturnInspectionRequest(InventoryMatch inventoryMatch, DamageClassification damageClassification,
        BigDecimal damageFee, BigDecimal cleaningFee, BigDecimal lostItemFee, BigDecimal overdueFee,
        BigDecimal outstandingFee, String staffNotes, List<String> evidencePhotos, boolean returnedKey,
        boolean returnedCard, boolean returnedLock, StorageUnitStatus proposedUnitStatus, boolean unitVacated,
        List<DamageItem> damageItems, Integer missingPalletCount) {
        this(inventoryMatch, damageClassification, damageFee, cleaningFee, lostItemFee, overdueFee,
            outstandingFee, staffNotes, evidencePhotos, returnedKey, returnedCard, returnedLock, proposedUnitStatus,
            unitVacated, null, true, null, damageItems, null, missingPalletCount);
    }

    public ReturnInspectionRequest(InventoryMatch inventoryMatch, DamageClassification damageClassification,
        BigDecimal damageFee, BigDecimal cleaningFee, BigDecimal lostItemFee, BigDecimal overdueFee,
        BigDecimal outstandingFee, String staffNotes, List<String> evidencePhotos, boolean returnedKey,
        boolean returnedCard, boolean returnedLock, StorageUnitStatus proposedUnitStatus, boolean unitVacated) {
        this(inventoryMatch, damageClassification, damageFee, cleaningFee, lostItemFee, overdueFee,
            outstandingFee, staffNotes, evidencePhotos, returnedKey, returnedCard, returnedLock, proposedUnitStatus,
            unitVacated, null, true, null, null, null, null);
    }

    /** Recovery flow: handover location and the second (witness) staff member. */
    public ReturnInspectionRequest(InventoryMatch inventoryMatch, DamageClassification damageClassification,
        BigDecimal damageFee, BigDecimal cleaningFee, BigDecimal lostItemFee, BigDecimal overdueFee,
        BigDecimal outstandingFee, String staffNotes, List<String> evidencePhotos, boolean returnedKey,
        boolean returnedCard, boolean returnedLock, StorageUnitStatus proposedUnitStatus, boolean unitVacated,
        String recoveryHandoverLocation, boolean customerPresent) {
        this(inventoryMatch, damageClassification, damageFee, cleaningFee, lostItemFee, overdueFee,
            outstandingFee, staffNotes, evidencePhotos, returnedKey, returnedCard, returnedLock,
            proposedUnitStatus, unitVacated, recoveryHandoverLocation, customerPresent, null, null, null, null);
    }

    /** Recovery flow: handover location, customer presence and the second (witness) staff member. */
    public ReturnInspectionRequest(InventoryMatch inventoryMatch, DamageClassification damageClassification,
        BigDecimal damageFee, BigDecimal cleaningFee, BigDecimal lostItemFee, BigDecimal overdueFee,
        BigDecimal outstandingFee, String staffNotes, List<String> evidencePhotos, boolean returnedKey,
        boolean returnedCard, boolean returnedLock, StorageUnitStatus proposedUnitStatus, boolean unitVacated,
        String recoveryHandoverLocation, boolean customerPresent, UUID recoveryWitnessStaffId) {
        this(inventoryMatch, damageClassification, damageFee, cleaningFee, lostItemFee, overdueFee,
            outstandingFee, staffNotes, evidencePhotos, returnedKey, returnedCard, returnedLock,
            proposedUnitStatus, unitVacated, recoveryHandoverLocation, customerPresent,
            recoveryWitnessStaffId, null, null, null);
    }

    /** Backward-compatible constructor used by existing tests and damage-item callers. */
    public ReturnInspectionRequest(InventoryMatch inventoryMatch, DamageClassification damageClassification,
        BigDecimal damageFee, BigDecimal cleaningFee, BigDecimal lostItemFee, BigDecimal overdueFee,
        BigDecimal outstandingFee, String staffNotes, List<String> evidencePhotos, boolean returnedKey,
        boolean returnedCard, boolean returnedLock, StorageUnitStatus proposedUnitStatus, boolean unitVacated,
        List<DamageItem> damageItems) {
        this(inventoryMatch, damageClassification, damageFee, cleaningFee, lostItemFee, overdueFee,
            outstandingFee, staffNotes, evidencePhotos, returnedKey, returnedCard, returnedLock,
            proposedUnitStatus, unitVacated, null, true, null, damageItems, null, null);
    }
}
