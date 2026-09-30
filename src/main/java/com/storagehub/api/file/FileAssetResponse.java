package com.storagehub.api.file;

import com.storagehub.domain.model.FileAssetStatus;
import java.time.Instant;
import java.util.UUID;

public record FileAssetResponse(
    UUID id,
    String originalName,
    String contentType,
    long sizeBytes,
    String checksumSha256,
    String entityType,
    UUID entityId,
    FileAssetStatus status,
    Instant createdAt
) {
}
