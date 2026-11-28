package com.storagehub.api.facility;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ManagerUnitTypeRequest(
    @Size(max = 20) String code,
    @NotBlank @Size(max = 100) String name,
    @Size(max = 500) String description,
    @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal lengthM,
    @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal widthM,
    @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal heightM,
    @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal maxLoadKg,
    @Min(0) int rackCount,
    @NotNull @DecimalMin("0.01") BigDecimal rackLengthM,
    @NotNull @DecimalMin("0.01") BigDecimal rackWidthM,
    @NotNull @DecimalMin("0.01") BigDecimal rackHeightM,
    @NotNull @DecimalMin("0.01") BigDecimal aisleWidthM,
    @NotBlank @Size(max = 160) String trolleyType,
    @Min(0) int smallBoxCapacity,
    @Min(0) int largeBoxCapacity,
    @Size(max = 2800000, message = "Ảnh loại kho quá lớn; vui lòng chọn ảnh không quá 2 MB.") String imageUrl,
    @Min(0) int physicalUnitCount,
    @Size(max = 32) String floor,
    @Size(max = 32) String zone,
    @Size(max = 2800000, message = "Ảnh chi tiết quá lớn; vui lòng chọn ảnh không quá 2 MB.") String detailImageUrl,
    List<UUID> acceptedGoodsCategoryIds,
    List<UUID> acceptedGoodsMaterialIds,
    @Valid RackLayout.Save rackLayout,
    @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal maxStoredLoadKg,
    @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal storageAisleWidthM,
    @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal gateWidthM,
    @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal gateHeightM,
    @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal lowestObstructionHeightM,
    @DecimalMin("0.0") @Digits(integer = 10, fraction = 2) BigDecimal overheadClearanceM,
    /** v8.1 input aliases. New clients may submit centimetres; the compact
     * constructor maps them to the existing metre-based persistence model. */
    @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal lengthCm,
    @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal widthCm,
    @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal heightCm,
    @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal storageAisleWidthCm,
    @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal gateWidthCm,
    @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal gateHeightCm,
    @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal lowestObstructionHeightCm,
    @DecimalMin("0.0") @Digits(integer = 10, fraction = 2) BigDecimal overheadClearanceCm,
    @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal rackLengthCm,
    @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal rackWidthCm,
    @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal rackHeightCm,
    @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal aisleWidthCm
) {
    public ManagerUnitTypeRequest {
        if (lengthM == null && lengthCm != null) lengthM = lengthCm.movePointLeft(2);
        if (widthM == null && widthCm != null) widthM = widthCm.movePointLeft(2);
        if (heightM == null && heightCm != null) heightM = heightCm.movePointLeft(2);
        if (lengthCm == null && lengthM != null) lengthCm = lengthM.movePointRight(2);
        if (widthCm == null && widthM != null) widthCm = widthM.movePointRight(2);
        if (heightCm == null && heightM != null) heightCm = heightM.movePointRight(2);
        if (storageAisleWidthM == null && storageAisleWidthCm != null) storageAisleWidthM = storageAisleWidthCm.movePointLeft(2);
        if (gateWidthM == null && gateWidthCm != null) gateWidthM = gateWidthCm.movePointLeft(2);
        if (gateHeightM == null && gateHeightCm != null) gateHeightM = gateHeightCm.movePointLeft(2);
        if (lowestObstructionHeightM == null && lowestObstructionHeightCm != null) lowestObstructionHeightM = lowestObstructionHeightCm.movePointLeft(2);
        if (overheadClearanceM == null && overheadClearanceCm != null) overheadClearanceM = overheadClearanceCm.movePointLeft(2);
        if (rackLengthM == null && rackLengthCm != null) rackLengthM = rackLengthCm.movePointLeft(2);
        if (rackWidthM == null && rackWidthCm != null) rackWidthM = rackWidthCm.movePointLeft(2);
        if (rackHeightM == null && rackHeightCm != null) rackHeightM = rackHeightCm.movePointLeft(2);
        if (aisleWidthM == null && aisleWidthCm != null) aisleWidthM = aisleWidthCm.movePointLeft(2);
        if (storageAisleWidthCm == null && storageAisleWidthM != null) storageAisleWidthCm = storageAisleWidthM.movePointRight(2);
        if (gateWidthCm == null && gateWidthM != null) gateWidthCm = gateWidthM.movePointRight(2);
        if (gateHeightCm == null && gateHeightM != null) gateHeightCm = gateHeightM.movePointRight(2);
        if (lowestObstructionHeightCm == null && lowestObstructionHeightM != null) lowestObstructionHeightCm = lowestObstructionHeightM.movePointRight(2);
        if (overheadClearanceCm == null && overheadClearanceM != null) overheadClearanceCm = overheadClearanceM.movePointRight(2);
        if (rackLengthCm == null && rackLengthM != null) rackLengthCm = rackLengthM.movePointRight(2);
        if (rackWidthCm == null && rackWidthM != null) rackWidthCm = rackWidthM.movePointRight(2);
        if (rackHeightCm == null && rackHeightM != null) rackHeightCm = rackHeightM.movePointRight(2);
        if (aisleWidthCm == null && aisleWidthM != null) aisleWidthCm = aisleWidthM.movePointRight(2);
    }

    public ManagerUnitTypeRequest(String code, String name, String description, BigDecimal lengthM,
        BigDecimal widthM, BigDecimal heightM, BigDecimal maxLoadKg, int rackCount,
        BigDecimal rackLengthM, BigDecimal rackWidthM, BigDecimal rackHeightM, BigDecimal aisleWidthM,
        String trolleyType, int smallBoxCapacity, int largeBoxCapacity, String imageUrl,
        int physicalUnitCount, String floor, String zone, String detailImageUrl,
        List<UUID> acceptedGoodsCategoryIds, List<UUID> acceptedGoodsMaterialIds) {
        this(code, name, description, lengthM, widthM, heightM, maxLoadKg, rackCount,
            rackLengthM, rackWidthM, rackHeightM, aisleWidthM, trolleyType, smallBoxCapacity,
            largeBoxCapacity, imageUrl, physicalUnitCount, floor, zone, detailImageUrl,
            acceptedGoodsCategoryIds, acceptedGoodsMaterialIds, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public ManagerUnitTypeRequest(String code, String name, String description, BigDecimal lengthM,
        BigDecimal widthM, BigDecimal heightM, BigDecimal maxLoadKg, int rackCount,
        BigDecimal rackLengthM, BigDecimal rackWidthM, BigDecimal rackHeightM, BigDecimal aisleWidthM,
        String trolleyType, int smallBoxCapacity, int largeBoxCapacity, String imageUrl,
        int physicalUnitCount, String floor, String zone, String detailImageUrl) {
        this(code, name, description, lengthM, widthM, heightM, maxLoadKg, rackCount,
            rackLengthM, rackWidthM, rackHeightM, aisleWidthM, trolleyType, smallBoxCapacity,
            largeBoxCapacity, imageUrl, physicalUnitCount, floor, zone, detailImageUrl, List.of(), List.of());
    }

    public ManagerUnitTypeRequest(String code, String name, String description, BigDecimal lengthM,
        BigDecimal widthM, BigDecimal heightM, BigDecimal maxLoadKg, int rackCount,
        BigDecimal rackLengthM, BigDecimal rackWidthM, BigDecimal rackHeightM, BigDecimal aisleWidthM,
        String trolleyType, int smallBoxCapacity, int largeBoxCapacity, String imageUrl,
        int physicalUnitCount, String floor, String zone, String detailImageUrl,
        List<UUID> acceptedGoodsCategoryIds, List<UUID> acceptedGoodsMaterialIds,
        BigDecimal maxStoredLoadKg) {
        this(code, name, description, lengthM, widthM, heightM, maxLoadKg, rackCount,
            rackLengthM, rackWidthM, rackHeightM, aisleWidthM, trolleyType, smallBoxCapacity,
            largeBoxCapacity, imageUrl, physicalUnitCount, floor, zone, detailImageUrl,
            acceptedGoodsCategoryIds, acceptedGoodsMaterialIds, null, maxStoredLoadKg, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Backwards-compatible overload for callers that already provide a saved rack layout. */
    public ManagerUnitTypeRequest(String code, String name, String description, BigDecimal lengthM,
        BigDecimal widthM, BigDecimal heightM, BigDecimal maxLoadKg, int rackCount,
        BigDecimal rackLengthM, BigDecimal rackWidthM, BigDecimal rackHeightM, BigDecimal aisleWidthM,
        String trolleyType, int smallBoxCapacity, int largeBoxCapacity, String imageUrl,
        int physicalUnitCount, String floor, String zone, String detailImageUrl,
        List<UUID> acceptedGoodsCategoryIds, List<UUID> acceptedGoodsMaterialIds,
        RackLayout.Save rackLayout) {
        this(code, name, description, lengthM, widthM, heightM, maxLoadKg, rackCount,
            rackLengthM, rackWidthM, rackHeightM, aisleWidthM, trolleyType, smallBoxCapacity,
            largeBoxCapacity, imageUrl, physicalUnitCount, floor, zone, detailImageUrl,
            acceptedGoodsCategoryIds, acceptedGoodsMaterialIds, rackLayout, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null);
    }

}
