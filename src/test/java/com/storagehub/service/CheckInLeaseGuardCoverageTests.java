package com.storagehub.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every staff write on a check-in must go through the processing lease.
 *
 * {@code ProcessingLeaseService.command} bumps the record revision and returns the new
 * X-Processing-Version / X-Processing-Stamp. A write path without {@link ProcessingGuard} still
 * changes updated_at, so the stamp the browser holds stops matching and the next step is refused
 * with "Hồ sơ đã thay đổi" - the operator is locked out of a record they own.
 *
 * This test reflects over the service so a new endpoint cannot silently reintroduce the bug.
 */
class CheckInLeaseGuardCoverageTests {

    /** Read-only entries: fine without a guard because they never mutate the record. */
    private static final List<String> READ_ONLY = List.of(
        "list", "inventoryAlternatives", "pendingArrivals", "refusalWitnesses",
        "pendingInventoryRefusals", "getInventoryDraft", "inventoryDraft",
        "signatureRefusalReviews", "arrivalReviews", "toResponse", "latestAssignment",
        "compareIdentity", "downloadEvidence", "evidenceAssets"
    );

    /** Internal helpers and scheduled entry points, not browser write paths. */
    private static final List<String> NOT_HTTP_WRITES = List.of(
        "processAttendanceDeadline", "archiveCompleted"
    );

    @Test
    void everyStaffWriteOnACheckInIsGuarded() {
        List<String> unguarded = new ArrayList<>();
        for (Method method : CheckInHandoverService.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            String name = method.getName();
            if (READ_ONLY.contains(name) || NOT_HTTP_WRITES.contains(name)) continue;
            if (guarded(method)) continue;
            unguarded.add(name);
        }

        assertThat(unguarded)
            .as("these public write paths would change the record without bumping its lease "
                + "revision, so the browser's stamp drifts and the next step is refused")
            .isEmpty();
    }

    /**
     * The reminder and receipt-release actions were the last unguarded pair. Both mutate the
     * check-in, and the reminder silently invalidated the stamp right before the operator pressed
     * the next step.
     */
    @Test
    void receiptReminderAndReleaseAreGuarded() {
        assertThat(guarded("remindCustomerReceipt")).isTrue();
        assertThat(guarded("releaseAfterCustomerReceiptTimeout")).isTrue();
    }

    private boolean guarded(String methodName) {
        for (Method method : CheckInHandoverService.class.getDeclaredMethods())
            if (method.getName().equals(methodName) && guarded(method)) return true;
        return false;
    }

    private boolean guarded(Method method) {
        return Arrays.stream(method.getAnnotations())
            .anyMatch(a -> a.annotationType().getName().endsWith("ProcessingGuard"));
    }
}
