package com.storagehub.api.configuration;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RentalPackagePolicyResponse(
    UUID id,
    UUID facilityId,
    String facilityName,
    String code,
    String name,
    int rentalMonths,
    BigDecimal discountRate,
    String policyVersion,
    boolean active,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    Instant createdAt,
    Instant updatedAt
) {}
