package com.storagehub.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.storagehub.domain.model.Rental;

/**
 * Legacy-compatible helper updated with the unified 120h/168h/216h/288h (5/7/9/12 days)
 * arrival deadlines starting from receivedAt (setupStartedAt) instead of free setup end.
 */
public final class SetupCompletionPolicy {
    private SetupCompletionPolicy() {}

    public static int[] days(String code) {
        if (code == null) return null;
        return switch (code.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "S" -> new int[]{5, 2};
            case "M" -> new int[]{7, 3};
            case "L" -> new int[]{9, 4};
            case "XL" -> new int[]{12, 5};
            default -> null;
        };
    }

    public static void initialize(Rental rental) {
        if (rental == null || rental.getOriginalInspectionArrivalDeadlineAt() != null
            || rental.getStorageUnit() == null || rental.getStorageUnit().getUnitType() == null) return;
        Instant receivedAt = rental.getSetupStartedAt() != null ? rental.getSetupStartedAt() : rental.getSetupEndsAt();
        if (receivedAt == null) return;
        int[] policy = days(rental.getStorageUnit().getUnitType().getCode());
        if (policy == null) return;
        Instant d0 = receivedAt.plus(policy[0] * 24L, ChronoUnit.HOURS);
        rental.setIntakePolicyVersion(IntakePolicyProperties.POLICY_VERSION);
        rental.setIntakeInitialDurationHours(policy[0] * 24);
        rental.setIntakeExtensionMaxHours(policy[1] * 24);
        rental.setIntakeExtensionMaxCount(2);
        rental.setIntakeCompletionHours(72);
        rental.setOriginalInspectionArrivalDeadlineAt(d0);
        rental.setInventoryFinalDeadlineAt(d0);
        rental.setSetupCompletionDeadlineAt(d0);
        rental.setSetupExtensionDays(policy[1]);
        rental.setSetupExtensionCount(0);
    }

    public static boolean canExtend(Rental rental, Instant now, Instant requested) {
        if (rental == null || now == null || requested == null) return false;
        Instant d = rental.getInventoryFinalDeadlineAt() != null
            ? rental.getInventoryFinalDeadlineAt()
            : rental.getSetupCompletionDeadlineAt();
        if (d == null || rental.getInspectionClockStartedAt() != null
            || rental.getFirstIdentityVerificationAt() != null
            || rental.getInventoryArrivedAt() != null || rental.getActualArrivedAt() != null
            || rental.getInventoryEscalatedAt() != null) return false;
        int maxDays = rental.getIntakeExtensionMaxHours() != null
            ? rental.getIntakeExtensionMaxHours() / 24
            : (rental.getSetupExtensionDays() != null ? rental.getSetupExtensionDays() : 0);
        int count = rental.getSetupExtensionCount() != null ? rental.getSetupExtensionCount() : 0;
        if (maxDays <= 0 || count >= 2) return false;
        return now.isBefore(d)
            && requested.isAfter(d)
            && !requested.isAfter(d.plus(maxDays * 24L, ChronoUnit.HOURS));
    }
}
