package com.storagehub.api.notification;

import com.storagehub.domain.model.NotificationType;
import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
    UUID id,
    UUID userId,
    NotificationType type,
    String title,
    String content,
    UUID relatedEntityId,
    boolean isRead,
    Instant createdAt
) {
}
