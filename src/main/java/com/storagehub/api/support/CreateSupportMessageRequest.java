package com.storagehub.api.support;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSupportMessageRequest(
    @NotBlank @Size(max = 4000) String body
) {}
