package com.storagehub.api.reservation;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record CheckInAppointmentRequest(
    @NotNull @FutureOrPresent Instant appointmentAt
) {
}
