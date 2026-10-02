package com.storagehub.api.facility;

import java.time.LocalDate;
import java.util.UUID;

public record AvailabilityResponse(
    UUID facilityId,
    UUID unitTypeId,
    LocalDate startDate,
    LocalDate endDate,
    long availableCount,
    boolean available
) {
}
