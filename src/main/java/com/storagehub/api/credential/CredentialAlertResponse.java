package com.storagehub.api.credential;

import com.storagehub.domain.model.AccessCredentialType;
import java.time.Instant;
import java.util.UUID;

public record CredentialAlertResponse(
    UUID id,
    String action,
    UUID reservationId,
    String reservationCode,
    UUID rentalId,
    UUID facilityId,
    String facilityName,
    UUID storageUnitId,
    String storageUnitCode,
    UUID customerId,
    String customerName,
    AccessCredentialType credentialType,
    String reference,
    Instant dueAt,
    String reason
) {}
