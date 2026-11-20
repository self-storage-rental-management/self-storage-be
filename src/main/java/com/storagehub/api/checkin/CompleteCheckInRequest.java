package com.storagehub.api.checkin;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.storagehub.validation.IdentityDocumentPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CompleteCheckInRequest(
    @NotNull @Valid CheckInChecklist checklist,
    @NotBlank @Size(max = 30) String identityDocumentNumber,
    @Size(max = 1000) String identityVerificationNote,
    @NotNull @Valid CheckInMeasurements actualMeasurements,
    @Size(max = 1000) String varianceReason,
    @NotBlank @Size(max = 1000) String initialUnitCondition,
    @NotBlank @Size(max = 1000) String goodsCondition,
    @Min(1) int packageCount,
    @NotBlank @Size(max = 160) String goodsCategory,
    @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 500) String> evidenceReferences,
    @NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 200) String> handedOverItems,
    @Size(max = 2000) String notes,
    @NotBlank @Size(max = 160) String lockReference,
    List<@NotNull @Valid InventoryLine> inventoryLines
) {
    public record InventoryLine(
        @NotNull UUID goodsItemId,
        @NotNull @Min(0) Integer actualQuantity,
        @NotNull Condition condition,
        @Size(max = 1000) String note,
        @Valid InventoryMeasurements measurements
    ) {
        public enum Condition { INTACT, DAMAGED, NOT_PRESENT }

        public InventoryLine(UUID goodsItemId, Integer actualQuantity, Condition condition, String note) {
            this(goodsItemId, actualQuantity, condition, note, null);
        }
    }

    public record InventoryMeasurements(
        @NotNull @DecimalMin("0.01") BigDecimal lengthCm,
        @NotNull @DecimalMin("0.01") BigDecimal widthCm,
        @NotNull @DecimalMin("0.01") BigDecimal heightCm,
        @NotNull @DecimalMin("0.01") BigDecimal weightKg
    ) {}
    @JsonIgnore
    @AssertTrue(message = IdentityDocumentPolicy.MESSAGE)
    public boolean isIdentityDocumentNumberValid() {
        return IdentityDocumentPolicy.isValid(identityDocumentNumber);
    }

    /** Compatibility constructor for older internal callers; new requests must send lockReference. */
    public CompleteCheckInRequest(
        CheckInChecklist checklist,
        String identityDocumentNumber,
        String identityVerificationNote,
        CheckInMeasurements actualMeasurements,
        String varianceReason,
        String initialUnitCondition,
        String goodsCondition,
        int packageCount,
        String goodsCategory,
        List<String> evidenceReferences,
        List<String> handedOverItems,
        String notes
    ) {
        this(checklist, identityDocumentNumber, identityVerificationNote, actualMeasurements,
            varianceReason, initialUnitCondition, goodsCondition, packageCount, goodsCategory,
            evidenceReferences, handedOverItems, notes, "LEGACY-LOCK", null);
    }
}
