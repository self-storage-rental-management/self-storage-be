package db.renewalrecoverymigration;

public class V2026101603__ensure_renewal_deposit_forfeitures extends db.migration.DevelopRenewalHistoryBridge {
    public V2026101603__ensure_renewal_deposit_forfeitures() {
        super("2026101501", "V2026101501__record_renewal_deposit_forfeitures.sql",
            "/db/migration-history/legacy-deposit-1501/V2026101501__record_renewal_deposit_forfeitures.sql");
    }
}
