package com.storagehub.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.storagehub.domain.model.Rental;

public final class SetupCompletionPolicy {
    private SetupCompletionPolicy() {}
    public static int[] days(String code) {
        if (code == null) return null;
        return switch (code.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "S" -> new int[]{3, 2};
            case "M" -> new int[]{5, 3};
            case "L" -> new int[]{7, 4};
            case "XL" -> new int[]{10, 5};
            default -> null;
        };
    }
    public static void initialize(Rental rental) {
        if (rental.getSetupCompletionDeadlineAt() != null || rental.getSetupEndsAt() == null
            || rental.getStorageUnit() == null || rental.getStorageUnit().getUnitType() == null) return;
        int[] policy = days(rental.getStorageUnit().getUnitType().getCode());
        if (policy == null) return;
        rental.setSetupCompletionDeadlineAt(rental.getSetupEndsAt().plus(policy[0], ChronoUnit.DAYS));
        rental.setSetupExtensionDays(policy[1]);
        rental.setSetupExtensionCount(0);
    }
    public static boolean canExtend(Rental rental, Instant now, Instant requested) {
        return rental.getMoveInCompletedAt() == null && rental.getSetupCompletionDeadlineAt() != null
            && rental.getSetupExtensionDays() != null && rental.getSetupExtensionCount() != null
            && rental.getSetupExtensionCount() < 2 && now.isBefore(rental.getSetupCompletionDeadlineAt())
            && requested != null && requested.isAfter(rental.getSetupCompletionDeadlineAt())
            && !requested.isAfter(rental.getSetupCompletionDeadlineAt().plus(rental.getSetupExtensionDays(), ChronoUnit.DAYS));
    }
}
