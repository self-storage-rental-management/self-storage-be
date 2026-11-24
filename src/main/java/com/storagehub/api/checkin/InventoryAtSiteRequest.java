package com.storagehub.api.checkin;

import com.storagehub.api.reservation.InventoryResolutionRequest;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InventoryAtSiteRequest(@NotNull InventoryResolutionRequest.Decision decision,
    @NotNull @AssertTrue Boolean customerPresent, @Size(max = 1500) String note, LocalDate scheduledDate,
    @NotEmpty @Size(max = 10) List<@NotNull UUID> evidenceReferences, @NotNull @PositiveOrZero Long resultVersion) {}
