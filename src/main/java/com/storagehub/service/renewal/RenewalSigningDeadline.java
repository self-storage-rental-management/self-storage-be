package com.storagehub.service.renewal;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Appointment duration is independent of the time allowed to complete Renewal. */
public final class RenewalSigningDeadline {
    public static final String REFERENCE = "RENEWAL_DEPOSIT_SIGNING_WINDOW";
    public static final String VERSION = "2";
    public static final Duration WINDOW = Duration.ofDays(3);
    private RenewalSigningDeadline() {}
    public static Instant calculate(Instant paidAt,Instant recoveryCutoff) {
        Instant deadline=Objects.requireNonNull(paidAt).plus(WINDOW);
        Objects.requireNonNull(recoveryCutoff);
        return (deadline.isBefore(recoveryCutoff)?deadline:recoveryCutoff).truncatedTo(ChronoUnit.MICROS);
    }
}
