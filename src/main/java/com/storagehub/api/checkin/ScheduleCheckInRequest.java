package com.storagehub.api.checkin;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record ScheduleCheckInRequest(
    @NotNull Instant scheduledAt,
    @AssertTrue(message = "contractVerified must be true") boolean contractVerified,
    @AssertTrue(message = "paymentVerified must be true") boolean paymentVerified,
    @Size(max = 1000) String note
) {
}
