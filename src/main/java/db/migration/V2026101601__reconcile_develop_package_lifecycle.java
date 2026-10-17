package db.migration;

public class V2026101601__reconcile_develop_package_lifecycle extends DevelopRenewalHistoryBridge {
    public V2026101601__reconcile_develop_package_lifecycle() {
        super("2026101501", "V2026101501__extend_rental_package_policy_lifecycle.sql",
            "/db/migration-history/develop-1501/V2026101501__extend_rental_package_policy_lifecycle.sql");
    }
}
