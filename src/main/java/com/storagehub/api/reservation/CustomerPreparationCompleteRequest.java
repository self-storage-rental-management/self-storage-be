package com.storagehub.api.reservation;

import jakarta.validation.constraints.Size;

public record CustomerPreparationCompleteRequest(
    @Size(max = 500) String note
) {
}
