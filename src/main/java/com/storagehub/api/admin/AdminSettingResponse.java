package com.storagehub.api.admin;

import java.time.Instant;
import java.util.List;

public record AdminSettingResponse(
    String id,
    String group,
    String description,
    String label,
    String type,
    Object value,
    List<String> options,
    Instant updatedAt
) {
}
