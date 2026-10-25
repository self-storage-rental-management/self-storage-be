package com.storagehub.api.configuration;

import com.storagehub.domain.model.PublicationStatus;
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
    Integer rentalYears,
    String description,
    BigDecimal discountRate,
    BigDecimal depositRate,
    String policyVersion,
    PublicationStatus publicationStatus,
    String runtimeStatus,
    boolean active,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    Instant createdAt,
    Instant updatedAt
) {
    public RentalPackagePolicyResponse(
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
    ) {
        this(
            id, facilityId, facilityName, code, name, rentalMonths,
            null, null, discountRate, new BigDecimal("0.20"), policyVersion,
            active ? PublicationStatus.PUBLISHED : PublicationStatus.INACTIVE,
            active ? "ACTIVE" : "INACTIVE",
            active, effectiveFrom, effectiveTo, createdAt, updatedAt
        );
    }
}
