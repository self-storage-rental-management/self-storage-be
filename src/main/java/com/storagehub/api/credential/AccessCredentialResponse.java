package com.storagehub.api.credential;

import com.storagehub.domain.model.AccessCredentialStatus;
import com.storagehub.domain.model.AccessCredentialType;
import java.time.Instant;
import java.util.UUID;

public record AccessCredentialResponse(
    UUID id,
    UUID reservationId,
    String reservationCode,
    UUID rentalId,
    UUID facilityId,
    String facilityName,
    UUID storageUnitId,
    String storageUnitCode,
    UUID customerId,
    String customerName,
    AccessCredentialType type,
    AccessCredentialStatus status,
    String reference,
    UUID issuedBy,
    String issuedByName,
    Instant issuedAt,
    UUID revokedBy,
    String revokedByName,
    Instant revokedAt,
    String note
) {}
