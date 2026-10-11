# Immutable history variants — develop preferred

`DevelopMigrationHistoryLocations` inspects only the actual Flyway datasource/history, then selects one immutable resource for each conflicting version. New databases use develop's `0909` VNPay and `1001` check-in appointment. Already-applied legacy scripts keep their original names/content/checksums. Flyway validation remains enabled.

Never add this root directory to Flyway locations: scanning all variants would duplicate versions. Spring Boot registers the customizer automatically. Standalone Java Flyway callers using `classpath:db/migration` must call `DevelopMigrationHistoryLocations.configure(configuration)` before `load()`.

| Folder | Selected when |
| --- | --- |
| develop-0909 | No 0909 history, or applied develop VNPay 0909 |
| legacy-0909 | Applied remove_legacy_rental_end_date 0909 |
| develop-1001 | No 1001 history, or applied check-in appointment 1001 |
| legacy-1001 | Applied ledger/outbox 1001 |
| applied-vnpay-0910 | Only when this exact 0910 SQL already succeeded; never a new migration |

Unknown/failed/duplicate history stops without repair, baseline, reset, ignore or out-of-order. SQL content is immutable; folder relocation does not change Flyway SQL checksum. `V2026101102` stays immutable and fills verified missing VNPay/appointment columns for legacy history. New `V2026101103` creates seven ledger/outbox tables on an empty integration schema, or reuses only the exact verified legacy ledger schema with matching applied history. Partial/orphan/drifted tables remain blocked.

Verified engine: MySQL 8.4.11. TiDB/shared DB is not certified or migrated by this change. See `docs/DEVELOP_PREFERRED_MIGRATION_REPORT_20261011.md`.
