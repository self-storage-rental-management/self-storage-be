package com.storagehub.api.configuration;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.storagehub.domain.model.PublicationStatus;

public record RentalPackagePolicyRequest(
    @NotNull(message = "facilityId is required")
    UUID facilityId,

    @NotBlank(message = "code is required")
    @Size(max = 30, message = "code cannot exceed 30 characters")
    String code,

    @NotBlank(message = "name is required")
    @Size(max = 100, message = "name cannot exceed 100 characters")
    String name,

    @jakarta.validation.constraints.Min(value = 1, message = "rentalMonths must be > 0")
    Integer rentalMonths,

    @jakarta.validation.constraints.Min(value = 1, message = "rentalYears must be > 0")
    Integer rentalYears,

    @Size(max = 500, message = "description cannot exceed 500 characters")
    String description,

    @NotNull(message = "discountRate is required")
    @DecimalMin(value = "0.0", message = "discountRate must be >= 0")
    @DecimalMax(value = "1.0", message = "discountRate must be <= 1.0 (100%)")
    BigDecimal discountRate,

    @NotBlank(message = "policyVersion is required")
    @Size(max = 50, message = "policyVersion cannot exceed 50 characters")
    String policyVersion,

    PublicationStatus publicationStatus,

    Boolean active,

    @NotNull(message = "effectiveFrom is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    LocalDate effectiveFrom,

    @JsonFormat(pattern = "yyyy-MM-dd")
    LocalDate effectiveTo
) {
    public RentalPackagePolicyRequest(
        UUID facilityId,
        String code,
        String name,
        int rentalMonths,
        BigDecimal discountRate,
        String policyVersion,
        Boolean active,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
    ) {
        this(facilityId, code, name, rentalMonths, null, null, discountRate, policyVersion, null, active, effectiveFrom, effectiveTo);
    }

    public int resolveRentalMonths() {
        if (rentalYears != null && rentalYears <= 0) {
            throw com.storagehub.common.api.ApiExceptions.validation("Rental duration in years must be greater than 0", null);
        }
        if (rentalMonths != null && rentalMonths <= 0) {
            throw com.storagehub.common.api.ApiExceptions.validation("Rental duration in months must be greater than 0", null);
        }
        if (rentalYears != null && rentalYears > 0) {
            return rentalYears * 12;
        }
        if (rentalMonths != null && rentalMonths > 0) {
            return rentalMonths;
        }
        throw com.storagehub.common.api.ApiExceptions.validation("Rental duration must be an integer greater than 0", null);
    }
}
