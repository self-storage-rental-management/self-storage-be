package com.storagehub.api.reservation;

import com.storagehub.domain.model.FileAssetStatus;
import java.time.Instant;
import java.util.UUID;

public record CheckInDocumentResponse(
    UUID id,
    String originalName,
    String contentType,
    long sizeBytes,
    String checksumSha256,
    String entityType,
    UUID entityId,
    FileAssetStatus status,
    Instant createdAt,
    Kind kind
) {
    public enum Kind {
        SIGNED_CONTRACT, PAYMENT_RECEIPT, HANDOVER_EVIDENCE, INVENTORY_EVIDENCE, OTHER
    }
}
