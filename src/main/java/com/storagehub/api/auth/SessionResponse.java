package com.storagehub.api.auth;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
    UUID id,
    String createdIp,
    String userAgent,
    Instant createdAt,
    Instant lastSeenAt,
    Instant expiresAt,
    Instant revokedAt,
    boolean active
) {
}
