package com.storagehub.api.admin;

import com.storagehub.domain.model.UserStatus;
import jakarta.validation.constraints.NotNull;

public record AdminStatusUpdateRequest(@NotNull UserStatus status) {
}
