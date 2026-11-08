package com.storagehub.api.admin;

import jakarta.validation.constraints.NotNull;

public record AdminUpdateSettingRequest(@NotNull Object value) {
}
