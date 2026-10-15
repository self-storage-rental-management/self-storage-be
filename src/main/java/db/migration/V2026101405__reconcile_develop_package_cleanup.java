package db.migration;
public class V2026101405__reconcile_develop_package_cleanup extends DevelopRenewalHistoryBridge {
    public V2026101405__reconcile_develop_package_cleanup() {
        super("2026101301","V2026101301__deactivate_duplicate_effective_rental_packages.sql",
            "/db/migration-history/develop-1301/V2026101301__deactivate_duplicate_effective_rental_packages.sql",
            "2026101401","db.migration.V2026101401__reconcile_develop_package_cleanup");
    }
}
