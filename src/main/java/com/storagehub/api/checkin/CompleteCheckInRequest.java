package com.storagehub.api.checkin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CompleteCheckInRequest(
    @NotNull @Valid CheckInChecklist checklist,
    @NotNull @Valid CheckInMeasurements actualMeasurements,
    @NotBlank @Size(max = 1000) String initialUnitCondition,
    @NotBlank @Size(max = 1000) String goodsCondition,
    @Min(1) int packageCount,
    @NotBlank @Size(max = 160) String goodsCategory,
    @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 500) String> evidenceReferences,
    @NotEmpty @Size(max = 20) List<@NotBlank @Size(max = 200) String> handedOverItems,
    @Size(max = 2000) String notes
) {
}
