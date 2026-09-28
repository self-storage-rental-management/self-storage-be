package com.storagehub.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record AdminUpdateSettingRequest(@NotNull JsonNode value) {
}
