package db.migration;

/** Completes the other feature without changing either original migration history. */
public class V2026102406__reconcile_customer_chat_sessions extends DevelopRenewalHistoryBridge {
    public V2026102406__reconcile_customer_chat_sessions() {
        super("2026102402", "V2026102402__customer_chat_sessions.sql",
            "/db/migration-history/develop-support-2402/V2026102402__customer_chat_sessions.sql");
    }
}
