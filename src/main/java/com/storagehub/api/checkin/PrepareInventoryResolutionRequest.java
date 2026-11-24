package com.storagehub.api.checkin;

import jakarta.validation.constraints.Min;

public record PrepareInventoryResolutionRequest(
    @Min(0) long expectedRevision
) {}
