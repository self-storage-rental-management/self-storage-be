# Superseded archive

The immutable develop SQL has moved to individual `db/migration-history/*` folders. New databases prefer develop's 0909 and 1001; existing history selects its own immutable variants through `DevelopMigrationHistoryLocations`. VNPay 0910 is resolved only when already applied.

Do not add this archive or the history root to Flyway locations. See `db/migration-history/README.md` and `docs/DEVELOP_PREFERRED_MIGRATION_REPORT_20261011.md`. The previous preserve-legacy-as-default plan is historical, not current rollout guidance.
