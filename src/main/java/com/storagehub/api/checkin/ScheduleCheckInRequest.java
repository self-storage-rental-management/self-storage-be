package com.storagehub.api.checkin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record ScheduleCheckInRequest(
    @NotNull Instant scheduledAt,
    boolean contractVerified,
    boolean paymentVerified,
    @Size(max = 1000) String note,
    UUID assignedStaffId
) {
    public ScheduleCheckInRequest(
        Instant scheduledAt,
        boolean contractVerified,
        boolean paymentVerified,
        String note
    ) {
        this(scheduledAt, contractVerified, paymentVerified, note, null);
    }
}
