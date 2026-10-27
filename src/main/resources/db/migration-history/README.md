# Immutable history variants — develop preferred

`DevelopMigrationHistoryLocations` inspects only the actual Flyway datasource/history, then selects one immutable resource for each conflicting version. New databases use develop's `0909` VNPay and `1001` check-in appointment. Already-applied legacy scripts keep their original names/content/checksums. Flyway validation remains enabled.

The same routing applies to develop SQL versions `2026101401`–`2026101403`, which collided with already-applied local Java versions. Their SQL is stored unchanged in individual `develop-1401`–`develop-1403` folders. Applied local Java identities are resolved for validation; bridges `2026101407`–`2026101409` apply the develop SQL once on those databases. New calendar and payment migrations use `2026101410` and `2026101411`; applied Java `2026101403` and `2026101404` retain their original identities and checksums.

Develop SQL `2026101404`–`2026101406` is also preserved unchanged under individual history folders. The router resolves each applied SQL or Java identity at its original version. Bridges `2026101412`–`2026101413` reconcile the older develop package/facility scripts, checking successful earlier Java bridges and their checksums before skipping work. Bridges `2026101414`–`2026101416` apply the new develop image-audit, detail-image and facility-policy SQL only where those versions belong to local Java history. Do not edit successful schema history or use repair to align these identities.

Never add this root directory to Flyway locations: scanning all variants would duplicate versions. Spring Boot registers the customizer automatically. Standalone Java Flyway callers using `classpath:db/migration` must call `DevelopMigrationHistoryLocations.configure(configuration)` before `load()`.

Reservation contact snapshot and code sequence scripts were renumbered in develop to `2026102002` and `2026102003`. Databases that already applied the identical SQL at `2026101900` and `2026101904` retain those immutable resources and do not execute the DDL again. Fresh databases use the develop versions. This routing only reads history and never repairs or rewrites it.

Some older local databases applied the reservation code sequence at `2026101901`, which now belongs to the renewal installment tables in develop. Those databases retain the exact old `2026101901` SQL and receive the renewal tables through the ordered bridge `2026101901.1`; the bridge runs before `2026101902`/`2026101903` and is enabled out of order when later migrations were already applied.

| Folder | Selected when |
| --- | --- |
| develop-0909 | No 0909 history, or applied develop VNPay 0909 |
| legacy-0909 | Applied remove_legacy_rental_end_date 0909 |
| develop-1001 | No 1001 history, or applied check-in appointment 1001 |
| legacy-1001 | Applied ledger/outbox 1001 |
| applied-vnpay-0910 | Only when this exact 0910 SQL already succeeded; never a new migration |

Unknown/failed/duplicate history stops without repair, baseline, reset, ignore or out-of-order. SQL content is immutable; folder relocation does not change Flyway SQL checksum. `V2026101102` stays immutable and fills verified missing VNPay/appointment columns for legacy history. New `V2026101103` creates seven ledger/outbox tables on an empty integration schema, or reuses only the exact verified legacy ledger schema with matching applied history. Partial/orphan/drifted tables remain blocked.

Verified engine: MySQL 8.4.11. TiDB/shared DB is not certified or migrated by this change. See `docs/RENTAL_SUPPORT_SCHEMA_ROLLOUT.md`.
