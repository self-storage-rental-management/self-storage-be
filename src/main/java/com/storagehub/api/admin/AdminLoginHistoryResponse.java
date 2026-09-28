package com.storagehub.api.admin;

import com.storagehub.domain.model.RoleCode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record AdminLoginHistoryResponse(
    UUID id,
    UUID userId,
    String fullName,
    String email,
    Set<RoleCode> roles,
    boolean success,
    String ipAddress,
    String userAgent,
    String failureReason,
    Instant occurredAt
) {
}
