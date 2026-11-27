package com.storagehub.api.admin;

import com.storagehub.domain.model.UserStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminStatusUpdateRequest(@NotNull UserStatus status, @Size(max = 1000) String reason) {
}
