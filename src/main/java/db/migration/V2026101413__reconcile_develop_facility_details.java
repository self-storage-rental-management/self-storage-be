package db.migration;
public class V2026101413__reconcile_develop_facility_details extends DevelopRenewalHistoryBridge {
    public V2026101413__reconcile_develop_facility_details() {
        super("2026101302","V2026101302__add_facility_operating_details.sql",
            "/db/migration-history/develop-1302/V2026101302__add_facility_operating_details.sql",
            "2026101402","db.migration.V2026101402__reconcile_develop_facility_details",
            "2026101406","db.migration.V2026101406__reconcile_develop_facility_details");
    }
}
