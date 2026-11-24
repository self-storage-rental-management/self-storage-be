package com.storagehub.api.checkin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
public record SaveInventoryDraftRequest(@NotNull @Valid CompleteCheckInRequest request, @Min(0) long expectedRevision) {}
