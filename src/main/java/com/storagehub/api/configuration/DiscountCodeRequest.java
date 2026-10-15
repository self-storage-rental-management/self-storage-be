package com.storagehub.api.configuration;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.storagehub.domain.model.DiscountScope;
import com.storagehub.domain.model.DiscountType;
import com.storagehub.domain.model.PublicationStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record DiscountCodeRequest(
    @NotBlank(message = "code is required")
    @Size(max = 50, message = "code cannot exceed 50 characters")
    String code,

    @NotBlank(message = "name is required")
    @Size(max = 150, message = "name cannot exceed 150 characters")
    String name,

    @Size(max = 500, message = "description cannot exceed 500 characters")
    String description,

    @NotNull(message = "scope is required")
    DiscountScope scope,

    UUID facilityId,

    UUID policyId,

    @NotNull(message = "discountType is required")
    DiscountType discountType,

    @NotNull(message = "discountValue is required")
    @DecimalMin(value = "0.01", message = "discountValue must be > 0")
    BigDecimal discountValue,

    @NotNull(message = "effectiveFrom is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    LocalDate effectiveFrom,

    @JsonFormat(pattern = "yyyy-MM-dd")
    LocalDate effectiveTo,

    PublicationStatus publicationStatus,

    Integer maxUses
) {}
