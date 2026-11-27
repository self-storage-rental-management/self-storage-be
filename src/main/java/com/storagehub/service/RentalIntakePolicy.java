package com.storagehub.service;

import com.storagehub.domain.model.Rental;
import com.storagehub.domain.model.StorageUnitStatus;

/** Physical intake progress is independent of the already agreed paid rental dates. */
public final class RentalIntakePolicy {
    public static final String INCOMPLETE = "Rental inventory and intake are not completed";

    private RentalIntakePolicy() {}

    public static boolean isPending(Rental rental) {
        if (rental == null) return true;
        var unit = rental.getStorageUnit();
        if (unit != null && (unit.getStatus() == StorageUnitStatus.assigned
            || unit.getStatus() == StorageUnitStatus.reserved)) return true;
        if (rental.isInventoryPending()) return true;
        // Historical occupied rentals without an intake timeline retain their existing behavior.
        boolean hasIntakeTimeline = rental.getSetupStartedAt() != null || rental.getMoveInCompletedAt() != null
            || rental.getInventoryDueAt() != null || rental.getInventoryFinalDeadlineAt() != null;
        return hasIntakeTimeline && rental.getInventoryVerifiedAt() == null;
    }
}
