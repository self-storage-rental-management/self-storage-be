package com.storagehub.api.reservation;

import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;

public record InventoryResolutionRequest(
    Decision decision,
    @Size(max = 2000) String note,
    @JsonFormat(pattern = "yyyy-MM-dd") LocalDate scheduledDate
) {
    public enum Decision {
        ACCEPT_FULL,
        REQUEST_TRANSFER,
        CANCEL_AND_RETURN
    }

    public InventoryResolutionRequest(Decision decision, String note) {
        this(decision, note, null);
    }
}
