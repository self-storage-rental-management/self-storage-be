package db.migration;

/** Completes the other feature without changing either original migration history. */
public class V2026102405__reconcile_support_retention extends DevelopRenewalHistoryBridge {
    public V2026102405__reconcile_support_retention() {
        super("2026102401", "V2026102401__support_handoff_retention.sql",
            "/db/migration-history/develop-support-2401/V2026102401__support_handoff_retention.sql");
    }
}
