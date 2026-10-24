package db.migration;

/** Completes the other feature without changing either original migration history. */
public class V2026102407__reconcile_goods_identity extends DevelopRenewalHistoryBridge {
    public V2026102407__reconcile_goods_identity() {
        super("2026102401", "V2026102401__snapshot_goods_catalog_identity.sql",
            "/db/migration-history/legacy-goods-2401/V2026102401__snapshot_goods_catalog_identity.sql");
    }
}
