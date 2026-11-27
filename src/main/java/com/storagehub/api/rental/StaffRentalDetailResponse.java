package com.storagehub.api.rental;

import java.util.UUID;

public record StaffRentalDetailResponse(RentalSummaryResponse rental, UUID reservationId,
    String reservationCode, String contractNumber) {}
