package com.storagehub.api.checkin;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CheckInMeasurements(
    @NotNull @DecimalMin(value = "0.01") BigDecimal lengthCm,
    @NotNull @DecimalMin(value = "0.01") BigDecimal widthCm,
    @NotNull @DecimalMin(value = "0.01") BigDecimal heightCm,
    @NotNull @DecimalMin(value = "0.01") BigDecimal weightKg,
    @NotNull @DecimalMin(value = "0.000001") BigDecimal actualVolumeM3,
    boolean varianceAccepted
) {
}
