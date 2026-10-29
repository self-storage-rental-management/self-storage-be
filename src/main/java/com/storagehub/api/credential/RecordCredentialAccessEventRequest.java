package com.storagehub.api.credential;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RecordCredentialAccessEventRequest(
    @NotNull(message = "eventType is required")
    CredentialAccessEventType eventType,
    @Size(max = 500, message = "note cannot exceed 500 characters")
    String note
) {}
