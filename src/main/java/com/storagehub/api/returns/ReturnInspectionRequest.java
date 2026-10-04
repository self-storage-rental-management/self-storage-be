package com.storagehub.api.returns;

import com.storagehub.domain.model.DamageClassification;
import com.storagehub.domain.model.InventoryMatch;
import com.storagehub.domain.model.StorageUnitStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

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

    StorageUnitStatus proposedUnitStatus
) {}
