# Local demo Renewal operational sources

Migration `V2026101105` lives in the opt-in `db/renewal-local-demo-migration` location and backs two additive local-demo sources used by D2/D3:

- Recovery handoffs accepted by this BE are persisted in `renewal_demo_recovery_cases`; an active handoff blocks Renewal. In this local demo, the table is authoritative only for Recovery cases created by this application. Recovery cases initiated in an external system are not covered and must not be treated as clear.
- Signing appointments are persisted in `renewal_demo_signing_appointments`. The local demo schedule is Asia/Ho_Chi_Minh, every calendar day, 08:00-17:00, 30-minute slots, one appointment at a time per facility. Facility row locking serializes reservations across BE instances.

`run-dev.ps1` opts in to both the migration location and source beans. `run-rental-support-dev.ps1` also opts in. The default application property and Flyway location remain disabled for these local-demo additions. These sources are not production calendar or cross-system Recovery certification. Do not enable them in another profile until official holidays, Recovery receiver coverage, and shared operational writer protocols are integrated.

Migration is additive and must be applied by Flyway after backup and history inspection. Do not reset or manually seed operational cases to make a test pass.
