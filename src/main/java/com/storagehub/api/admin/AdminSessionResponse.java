package com.storagehub.api.admin;

import java.time.Instant;
import java.util.UUID;

public record AdminSessionResponse(
    UUID id,
    UUID userId,
    String fullName,
    String email,
    String createdIp,
    String userAgent,
    Instant createdAt,
    Instant lastSeenAt,
    Instant expiresAt,
    Instant revokedAt,
    boolean active
) {
}
