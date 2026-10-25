package db.migration;

/** Completes the other feature without changing either original migration history. */
public class V2026102408__reconcile_material_names extends DevelopRenewalHistoryBridge {
    public V2026102408__reconcile_material_names() {
        super("2026102402", "V2026102402__support_catalog_material_names.sql",
            "/db/migration-history/legacy-goods-2402/V2026102402__support_catalog_material_names.sql");
    }
}
