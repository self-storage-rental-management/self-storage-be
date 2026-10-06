package com.storagehub.api.configuration;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RentalPackagePolicyRequest(
    @NotNull(message = "facilityId is required")
    UUID facilityId,

    @NotBlank(message = "code is required")
    @Size(max = 30, message = "code cannot exceed 30 characters")
    String code,

    @NotBlank(message = "name is required")
    @Size(max = 100, message = "name cannot exceed 100 characters")
    String name,

    @Min(value = 1, message = "rentalMonths must be at least 1")
    int rentalMonths,

    @NotNull(message = "discountRate is required")
    @DecimalMin(value = "0.0", message = "discountRate must be >= 0")
    @DecimalMax(value = "1.0", message = "discountRate must be <= 1.0 (100%)")
    BigDecimal discountRate,

    @NotBlank(message = "policyVersion is required")
    @Size(max = 50, message = "policyVersion cannot exceed 50 characters")
    String policyVersion,

    Boolean active,

    @NotNull(message = "effectiveFrom is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    LocalDate effectiveFrom,

    @JsonFormat(pattern = "yyyy-MM-dd")
    LocalDate effectiveTo
) {}
