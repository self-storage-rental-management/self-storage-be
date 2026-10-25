package com.storagehub.api.facility;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ManagerUnitTypeRequest(
    @Size(max = 20) String code,
    @NotBlank @Size(max = 100) String name,
    @Size(max = 500) String description,
    @NotNull @DecimalMin("0.01") BigDecimal lengthM,
    @NotNull @DecimalMin("0.01") BigDecimal widthM,
    @NotNull @DecimalMin("0.01") BigDecimal heightM,
    @NotNull @DecimalMin("0.01") BigDecimal maxLoadKg,
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
    List<UUID> acceptedGoodsMaterialIds
) {
    public ManagerUnitTypeRequest(String code, String name, String description, BigDecimal lengthM,
        BigDecimal widthM, BigDecimal heightM, BigDecimal maxLoadKg, int rackCount,
        BigDecimal rackLengthM, BigDecimal rackWidthM, BigDecimal rackHeightM, BigDecimal aisleWidthM,
        String trolleyType, int smallBoxCapacity, int largeBoxCapacity, String imageUrl,
        int physicalUnitCount, String floor, String zone, String detailImageUrl) {
        this(code, name, description, lengthM, widthM, heightM, maxLoadKg, rackCount,
            rackLengthM, rackWidthM, rackHeightM, aisleWidthM, trolleyType, smallBoxCapacity,
            largeBoxCapacity, imageUrl, physicalUnitCount, floor, zone, detailImageUrl, List.of(), List.of());
    }
}
