package com.storagehub.api.configuration;

import com.storagehub.domain.model.DiscountScope;
import com.storagehub.domain.model.DiscountType;
import com.storagehub.domain.model.PublicationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DiscountCodeResponse(
    UUID id,
    String code,
    String name,
    String description,
    DiscountScope scope,
    UUID facilityId,
    String facilityName,
    UUID policyId,
    String policyName,
    DiscountType discountType,
    BigDecimal discountValue,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    PublicationStatus publicationStatus,
    String runtimeStatus,
    Integer maxUses,
    int currentUses,
    UUID createdById,
    String createdByName,
    Instant createdAt,
    Instant updatedAt
) {}
