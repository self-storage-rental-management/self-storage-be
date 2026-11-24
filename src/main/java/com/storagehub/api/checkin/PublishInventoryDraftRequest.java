package com.storagehub.api.checkin;
import jakarta.validation.constraints.Min;
public record PublishInventoryDraftRequest(@Min(0) long expectedRevision) {}
