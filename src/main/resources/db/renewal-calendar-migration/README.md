# Staff Renewal signing calendar rollout

The local runner enables the Staff calendar by default. For other deployments, append `classpath:db/renewalcalendarmigration` to the existing Flyway locations, then enable `storagehub.integration.renewal-signing-calendar.enabled=true`. The calendar schema is now installed by Java migration 2026101403; the original SQL 2026101301 is archived unchanged under `db/migration-history/legacy-calendar-1301` for migration history compatibility.

The additive migration creates Staff mutexes and resource-bound Renewal reservations. Capacity is one signing customer per Staff, 30 minutes, across facilities. Calendar writes join an explicit READ_COMMITTED caller transaction; unsupported isolation fails closed. Hours come from the shared `ServiceCalendarPolicy`.

Quiesce prior calendar writers during rollout. Future historical SIGNING appointments without matching new reservations make calendar coverage UNKNOWN. The migration does not backfill, move, release or adopt old/demo appointments. A Customer may replace their own legacy appointment by reselecting a slot; reservation and appointment update commit together under the existing locks, without changing the deposit or deadline. Other uncovered historical appointments still block new reservations until reconciled. Do not bypass capacity checks with direct event writes.

The new provider replaces only the demo signing calendar. It does not replace the Recovery receiver or certify unrelated Staff tasks, Ledger history, or the coordinated extension-hold writer protocol. Those sources continue to gate live Renewal activation.

Local command after reviewing the local database backup/rollout:

```powershell
.\run-rental-support-dev.ps1 -EnableRenewalPayments
```

The runner fixes its datasource to `storagehub_local`, preserves existing migration locations and adds the Staff calendar location by default. `-EnableStaffSigningCalendar:$false` explicitly opts out for local development only. No shared MySQL migration or live UI test has been performed as part of this implementation.
