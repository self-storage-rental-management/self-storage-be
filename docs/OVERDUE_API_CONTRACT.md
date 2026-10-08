# Overdue API — D4

08/10/2026. Base `/api/manager/overdue-cases`, bearer JWT. MANAGER + VIEW_RENTALS/READ for queries; MANAGE_RENTALS/MANAGE for commands. Facility scope enforced before projection and pagination. No Admin/Business implicit Manager impersonation.

## Reference and projection

Case is a stable projection, NOT a newly generated business entity:
- `RENTAL_TERM:<canonical Rental UUID>`
- `PAYMENT_DUE:<canonical Rental UUID>:<canonical obligation UUID>`

PAYMENT_DUE comes from authoritative dueAt/outstanding/allocations; only serverNow>dueAt and outstanding>0. Future obligations and paid/zero balances excluded. No recurring debt from monthlyPrice. Financial adapter must return complete linked records; null is UNKNOWN, negative/duplicate/wrong Rental/currency/future checkedAt is inconsistency409, never repaired.

RENTAL_TERM comes from owner-verified inclusive lastPermittedDate and BO policy warning/serious/urgent/recovery bounds/calendar/cutoff. It excludes actualReturnedAt/completed Rentals but can include return_requested/inspection/closing while goods remain. Missing date/policy source does not infer semantics/backfill. Recovery eligibility uses actual term policy recovery start, not every debt day8.

## Routes

| Method/path | Contract |
|---|---|
| GET base | page0,size20 max100,facilityId,kind ALL/PAYMENT_DUE/RENTAL_TERM,search literal case-insensitive customer/unit/ref,sort priority/overdueDays/caseRef asc/desc; default priority desc + caseRef asc |
| GET `/{caseRef}` | Current scoped case; resolved409, missing source409, invisible404 |
| POST `/{caseRef}/follow-ups` | type NOTE/REMINDER,content1–2000,expectedVersion; Idempotency-Key;201 append-only coordination |
| GET `/{caseRef}/follow-ups` | page,size; real immutable history remains readable after resolution |
| POST `/{caseRef}/recovery-handoffs` | reason1–2000,expectedVersion; Idempotency-Key;201 only if actual authorized Recovery receiver persists ACK |

List uses standard data/pagination/correlationId plus `asOf` (server instant), `completeness` COMPLETE/PARTIAL and missingSources. An empty/partial list with missing obligations or term policy MUST NOT be interpreted as no debt. Unknown/repeated query and client asOf rejected400. Current implementation projects scoped Rentals and owner obligations before filtering/sorting/pagination; optimize with owner bulk query before large-scale rollout, preserving correct totals and scope.

Case fields include rental/facility/customer/unit, kind/overdueDays/priority, real obligation/outstanding/currency (null for TERM), policy/cutoff/recoveryEligible, followUpVersion. Version describes only this module's follow-up stream: no events=>0, successful append increments1. It is not proof of financial snapshot version.

NOTE does not mutate balances or notify. REMINDER requires transactional owner policy/outbox, real recipient derived from Rental, cooldown ref/version. Rental lock serializes competing Managers and reminder throttling across different keys. externalRef proves QUEUED notification, NOT successful delivery; delivery/retry stays with shared owner.

Recovery handoff requires RENTAL_TERM current policy eligibility and transactional authorized receiver. Receiver must recheck Return/assets/cutoff and deduplicate. Its actual RECEIVED/ALREADY_RECEIVED ref is persisted locally. No fake ACK, Recovery result, physical-unit AVAILABLE, access lock, debt waiver, Rental status change or completion by Dương. Actual Recovery execution/result/read integration belongs to owner; this endpoint is coordination only.

Fee assessment/waiver/new penalty and Customer obligation-payment endpoints are NOT exposed in MVP. Existing owner-assessed fees remain part of authoritative outstanding; refresh/follow-up does not charge again.

## Sources, persistence and rollout

`service/overdue/OverdueSources.java`: FinancialSource, TermSource (read-only BO policy/date provenance), ReminderSource (policy+transactional enqueue), RecoverySource (actual transactional receiver). No runtime mocks/defaults installed. Missing source=>PARTIAL read or409 DEFERRED_SOURCE command; not0/[] success.

Additive overdue_follow_up_states and overdue_follow_ups; history immutable, no delete/soft-delete in this task. Reuse D2 idempotency primitives with operation namespace overdue_follow_up/overdue_recovery and bound Rental+case payload. Current authorization before replay, replay before stale stream revision/current-overdue check. Events/audit/revision/replay and owner enqueue/receive commit or roll back together.

DDL review-only: docs/sql/renewal-operations-overdue-schema.sql. No shared table reshape/seed/backfill, no migration executed or scheduler activated. Full rollout requires policy/financial/notification/Recovery adapters, owner schema/lock review and MySQL/Return/Settlement/Recovery concurrency E2E.
