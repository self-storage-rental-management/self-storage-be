# Ledger E2E tracking

| Slice | Status | Actual production integration | Tests | Blocker |
|---|---|---|---|---|
| 1 — Rental activation → Ledger Account | DONE | `CustomerReservationService.confirmReceipt()` opens one account for a newly created Rental in the existing transaction; completed replay is idempotent and historical Rentals are not backfilled. | `CustomerReceiptPeriodCompatibilityTests` (5); `LedgerStoreJdbcTests` (27); `CustomerReceiptLedgerActivationIntegrationTests` on H2 (4) and MySQL `storagehub_local` (4). MySQL preflight: 8.4.11, Flyway `2026101105`, six ledger tables; `ddl-auto=validate`, Flyway/SQL init disabled, test transactions rolled back. | None for Slice 1. |
