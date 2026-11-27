package com.storagehub.api.configuration;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
public record UnitTypeDeadlinePolicy(@Min(1) @Max(365) int setupDeadlineDays, @Min(1) @Max(365) int setupMaxExtensionDays, @Min(1) @Max(365) int mismatchReturnDays, @Min(1) @Max(365) int mismatchReturnMaxExtensionDays) {}
