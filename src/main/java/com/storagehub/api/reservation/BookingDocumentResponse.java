package com.storagehub.api.reservation;

import com.storagehub.domain.model.BookingDocumentType;
import java.time.Instant;
import java.util.UUID;

public record BookingDocumentResponse(
    UUID id,
    UUID reservationId,
    String reservationCode,
    BookingDocumentType documentType,
    UUID fileId,
    String fileName,
    String contentType,
    long sizeBytes,
    String checksumSha256,
    Instant issuedAt,
    String downloadUrl
) {
}
