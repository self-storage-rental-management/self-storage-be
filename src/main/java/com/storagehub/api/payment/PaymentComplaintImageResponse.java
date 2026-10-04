package com.storagehub.api.payment;

import java.util.UUID;

public record PaymentComplaintImageResponse(
    UUID id,
    String originalName,
    String contentType,
    long sizeBytes,
    String downloadUrl
) {
}
