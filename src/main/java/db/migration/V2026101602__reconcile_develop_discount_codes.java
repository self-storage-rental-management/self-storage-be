package db.migration;

public class V2026101602__reconcile_develop_discount_codes extends DevelopRenewalHistoryBridge {
    public V2026101602__reconcile_develop_discount_codes() {
        super("2026101502", "V2026101502__create_discount_codes_schema.sql",
            "/db/migration-history/develop-1502/V2026101502__create_discount_codes_schema.sql");
    }
}
