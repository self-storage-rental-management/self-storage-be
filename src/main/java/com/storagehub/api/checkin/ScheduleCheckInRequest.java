package com.storagehub.api.checkin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record ScheduleCheckInRequest(
    @NotNull Instant scheduledAt,
    boolean contractVerified,
    boolean paymentVerified,
    @Size(max = 1000) String note
) {
}
