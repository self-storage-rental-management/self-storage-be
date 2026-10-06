package com.storagehub.api.checkin;

import jakarta.validation.constraints.AssertTrue;

public record CheckInChecklist(
    @AssertTrue(message = "identityVerified must be true") boolean identityVerified,
    @AssertTrue(message = "reservationMatched must be true") boolean reservationMatched,
    @AssertTrue(message = "contractVerified must be true") boolean contractVerified,
    @AssertTrue(message = "paymentVerified must be true") boolean paymentVerified,
    @AssertTrue(message = "measurementVerified must be true") boolean measurementVerified,
    @AssertTrue(message = "unitWalkthrough must be true") boolean unitWalkthrough,
    @AssertTrue(message = "conditionRecorded must be true") boolean conditionRecorded,
    @AssertTrue(message = "accessHandedOver must be true") boolean accessHandedOver
) {
}
