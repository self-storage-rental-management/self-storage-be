package com.storagehub.api.reservation;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record RentalStartConfirmation(
    LocalDate scheduledStartDate,
    LocalDate startDate,
    LocalDate lastPermittedDate,
    int rentalMonths
) {
    public record Request(@NotNull LocalDate startDate, @NotNull LocalDate lastPermittedDate) {}
}
