package com.storagehub.service;

import java.time.Duration;
import java.time.Instant;

/** Stable buckets ensure each reminder is delivered once, including after restarts. */
final class DeadlineReminderPolicy {
    private DeadlineReminderPolicy() {}

    static String handover(Instant due, Instant now) {
        if (!now.isBefore(due)) return null;
        Duration remaining = Duration.between(now, due);
        if (remaining.compareTo(Duration.ofHours(48)) > 0) return null;
        if (remaining.compareTo(Duration.ofHours(24)) > 0) return "R48";
        if (remaining.compareTo(Duration.ofHours(12)) > 0) return "R24";
        long bucket = (remaining.toMillis() + Duration.ofHours(2).toMillis() - 1) / Duration.ofHours(2).toMillis();
        return "R" + (bucket * 2);
    }

    static String assignment(Instant due, Instant now) {
        if (!now.isBefore(due)) return "O" + Duration.between(due, now).toHours();
        Duration remaining = Duration.between(now, due);
        if (remaining.compareTo(Duration.ofHours(2)) > 0) return null;
        if (remaining.compareTo(Duration.ofHours(1)) > 0) return "R2";
        return remaining.compareTo(Duration.ofMinutes(30)) > 0 ? "R1" : "R30M";
    }
}
