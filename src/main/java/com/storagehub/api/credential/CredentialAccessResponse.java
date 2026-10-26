package com.storagehub.api.credential;

import java.time.Instant;
import java.util.UUID;

public record CredentialAccessResponse(
    UUID credentialId,
    UUID rentalId,
    Instant accessedAt,
    boolean allowed,
    boolean duringSetup,
    String reason,
    String accessHours
) {}
