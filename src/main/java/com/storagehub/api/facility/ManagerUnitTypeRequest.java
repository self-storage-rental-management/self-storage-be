package com.storagehub.api.facility;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record ManagerUnitTypeRequest(
    @NotBlank @Size(max = 20) String code,
    @NotBlank @Size(max = 100) String name,
    @NotNull @DecimalMin("0.01") BigDecimal lengthM,
    @NotNull @DecimalMin("0.01") BigDecimal widthM,
    @NotNull @DecimalMin("0.01") BigDecimal heightM,
    @NotNull @DecimalMin("0.01") BigDecimal maxLoadKg,
    @Min(0) int rackCount,
    @NotNull @DecimalMin("0.01") BigDecimal rackLengthM,
    @NotNull @DecimalMin("0.01") BigDecimal rackWidthM,
    @NotNull @DecimalMin("0.01") BigDecimal rackHeightM,
    @Size(max = 1000) String imageUrl,
    @Min(0) int physicalUnitCount,
    @Size(max = 32) String floor,
    @Size(max = 32) String zone
) {}
