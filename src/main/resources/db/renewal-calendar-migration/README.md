# Staff Renewal signing calendar rollout

This location is opt-in. Append `classpath:db/renewal-calendar-migration` to the existing Flyway locations, then enable `storagehub.integration.renewal-signing-calendar.enabled=true`.

The additive migration creates Staff mutexes and resource-bound Renewal reservations. Capacity is one signing customer per Staff, 30 minutes, across facilities. Calendar writes join an explicit READ_COMMITTED caller transaction; unsupported isolation fails closed. Hours come from the shared `ServiceCalendarPolicy`.

Quiesce prior calendar writers during rollout. Future historical SIGNING appointments without matching new reservations make calendar coverage UNKNOWN. The migration does not backfill, move, release or adopt old/demo appointments. Reconcile them explicitly before accepting new reservations. Deploy all assignment writers with the reservation guard; do not bypass it with direct event writes.

The new provider replaces only the demo signing calendar. It does not replace the Recovery receiver or certify unrelated Staff tasks, Ledger history, or the coordinated extension-hold writer protocol. Those sources continue to gate live Renewal activation.

Local command after reviewing the local database backup/rollout:

```powershell
.\run-rental-support-dev.ps1 -EnableStaffSigningCalendar
```

The runner fixes its datasource to `storagehub_local`, preserves existing migration locations and adds this location only with the explicit switch. No shared MySQL migration or live UI test has been performed as part of this implementation.
