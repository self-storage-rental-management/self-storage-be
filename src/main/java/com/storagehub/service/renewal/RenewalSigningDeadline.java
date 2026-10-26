package com.storagehub.service.renewal;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Appointment duration is independent of the time allowed to complete Renewal. */
public final class RenewalSigningDeadline {
    public static final String REFERENCE = "RENEWAL_DEPOSIT_SIGNING_WINDOW";
    public static final String VERSION = "2";
    public static final String PACKAGE_VERSION = "3_PACKAGE_MONTHS_PLUS_TWO_CAP_14";
    public static final Duration WINDOW = Duration.ofDays(3);
    private RenewalSigningDeadline() {}
    public static Instant calculate(Instant paidAt,Instant recoveryCutoff) {
        Instant deadline=Objects.requireNonNull(paidAt).plus(WINDOW);
        Objects.requireNonNull(recoveryCutoff);
        return (deadline.isBefore(recoveryCutoff)?deadline:recoveryCutoff).truncatedTo(ChronoUnit.MICROS);
    }
    public static int daysForMonths(int months) {
        if (months < 1) throw new IllegalArgumentException("Rental months must be positive");
        return (int)Math.min(14L, (long)months + 2);
    }
    public static int days(com.storagehub.api.renewal.RenewalQuoteResponse.Terms terms) {
        if (terms.signingWindowDays() == null) return 3; // Preserve the accepted legacy window.
        int days = terms.signingWindowDays();
        if (days < 1 || days > 14) throw new IllegalArgumentException("Invalid accepted signing window");
        return days;
    }
    public static Duration window(com.storagehub.api.renewal.RenewalQuoteResponse.Terms terms) {
        return Duration.ofDays(days(terms));
    }
    public static String version(com.storagehub.api.renewal.RenewalQuoteResponse.Terms terms) {
        return terms.signingWindowDays() == null ? VERSION : PACKAGE_VERSION;
    }
    public static Instant calculate(Instant paidAt,Instant recoveryCutoff,com.storagehub.api.renewal.RenewalQuoteResponse.Terms terms) {
        Instant deadline = Objects.requireNonNull(paidAt).plus(window(terms));
        return (deadline.isBefore(Objects.requireNonNull(recoveryCutoff)) ? deadline : recoveryCutoff).truncatedTo(ChronoUnit.MICROS);
    }
}
