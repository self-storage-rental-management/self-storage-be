package com.storagehub.api.credential;

import java.time.Instant;
import java.util.UUID;

public record CredentialAccessEventResponse(
    UUID credentialId,
    UUID rentalId,
    UUID facilityId,
    CredentialAccessEventType eventType,
    Instant occurredAt,
    boolean allowed,
    String reason
) {}
