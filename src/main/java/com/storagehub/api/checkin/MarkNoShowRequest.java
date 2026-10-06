package com.storagehub.api.checkin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MarkNoShowRequest(
    @NotBlank @Size(max = 1000) String reason
) {
}
