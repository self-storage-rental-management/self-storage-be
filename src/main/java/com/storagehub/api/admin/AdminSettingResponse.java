package com.storagehub.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public record AdminSettingResponse(
    String id,
    String group,
    String description,
    String label,
    String type,
    JsonNode value,
    List<String> options,
    Instant updatedAt
) {
}
