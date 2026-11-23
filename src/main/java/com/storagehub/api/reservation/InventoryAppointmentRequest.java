package com.storagehub.api.reservation;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record InventoryAppointmentRequest(
    @NotNull @FutureOrPresent Instant appointmentAt,
    @Size(max = 500) String reason
) {}
