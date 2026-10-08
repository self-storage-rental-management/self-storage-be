# D5 — Support API contract

Status: additive backend implementation, 08/10/2026. Shared-source integrations and schema rollout remain gated. This document describes implemented behavior, not a claim that every dependency is deployed.

## Ownership and boundary

- Customer owns creation/public conversation, explicit reopen and resolution confirmation.
- Facility Manager owns facility-scoped assignment/reassignment and escalation coordination only.
- The currently assigned, active Facility Staff owns acceptance, processing, request for information and resolution.
- Business Operations owns SLA/close policy and working calendar. Support consumes policy; it does not create a separate policy or edit BO data.
- Shared module owners own payment/refund/return/maintenance/account/renewal/overdue results. A Support message is not proof that those operations succeeded.
- Existing `SupportTicket`, `SupportTicketStatus`, `User`, permissions, facility scopes and file service are reused unchanged. No parallel task assignment source, new backend, external API or seeded business records.

## Authentication and scope

All routes use the existing Bearer authentication and response envelopes (`ApiResponse`, `PageResponse`, `ApiErrorResponse`, correlation ID).

Current persisted user status/roles/permissions/facility scopes are rechecked, not just token claims. Customer must own the ticket and every linked record. Manager reads require `VIEW_SUPPORT` + READ scope; commands require `MANAGE_SUPPORT` + MANAGE scope. Staff must be current assignee, have `VIEW_SUPPORT` and `MANAGE_SUPPORT`, and OPERATE/MANAGE scope. Reassignment revokes the old Staff's reads, commands and cached command replay.

**Deployment gate:** current `RoleDataInitializer` does not grant `MANAGE_SUPPORT` to STAFF. This task intentionally does not change it. The permission owner must approve/grant that permission through the existing role mechanism before Staff can operate D5; absent the grant, API rejects access and Staff options exclude ineligible users. Do not use a manager token to bypass this.

Legacy null-facility tickets are excluded from scoped lists and return 404; there is no implied global triage permission. Scoped legacy tickets without verified workflow metadata can be read with `workflowReady=false` but not mutated. No automatic backfill.

## Routes

Prefix `C = /api/customer/support-tickets`, `M = /api/manager/support-tickets`, `S = /api/staff/support-tickets`.

| Method / route | Behavior |
| --- | --- |
| POST C | Create owned ticket; 201 |
| GET C, GET C/{id} | Owned list/detail |
| GET C/{id}/messages | PUBLIC messages only; exclude internal rows before count/pagination |
| POST C/{id}/messages | Public reply; 201 |
| POST C/{id}/close | Customer confirms resolved ticket, shared policy required |
| POST C/{id}/reopen | Explicit resolved → in_progress/open |
| POST C/{id}/follow-ups | New linked ticket for closed parent; 201 |
| GET M, GET M/{id} | Scoped list/detail |
| GET M/staff-options?facilityId=UUID | Eligible Staff IDs in managed facility; page/size |
| POST M/{id}/assignment | Assign/reassign by ID and reason |
| GET M/{id}/messages, /events, /escalations | Scoped internal/public views |
| POST M/{id}/escalations/{escalationId}/decision | ROUTE/REJECT only |
| GET S, GET S/{id} | Current assigned list/detail |
| POST S/{id}/accept | open → in_progress |
| GET S/{id}/messages, /events, /escalations | Current assignment views |
| POST S/{id}/messages | PUBLIC reply or INTERNAL note; 201 |
| POST S/{id}/request-information | in_progress → waiting_customer |
| POST S/{id}/resolution | in_progress → resolved, verified outcome |
| POST S/{id}/escalations | Request authorized module coordination; 201 |

There is no Manager resolution endpoint, Customer internal timeline, arbitrary recipient endpoint, client ACK/result endpoint, or public auto-close endpoint.

## Payload and retry rules

All POST commands require `Idempotency-Key` (1–100 nonblank characters). Scope: actor + command operation + key; resource ID and normalized request payload are fingerprinted. Identical retry returns the stored result, not a second mutation/notification. Different payload for the same key returns 409. Authorization is rechecked before replay; replay precedes fresh state/version checks. Keys are hashed in receipts. Retain receipts while retry guarantees are required; do not silently expire them.

Transitions/assignment/coordination require nonnegative `expectedVersion` from the latest ticket detail. Customer reply requires it specifically when resuming `waiting_customer`. Ordinary message appends do not require an expected version. Ticket pessimistic locks serialize append/transition/reassignment/auto-close; separate workflow `@Version` detects stale transitions, without adding a version column to the shared entity. Assignment revision increments on reassignment and acceptance is reset.

Unknown/repeated query parameters, unknown JSON properties, duplicate JSON keys, trailing JSON and bodies over 64 KiB are rejected only for D5 controllers. Existing modules' Jackson settings are not changed.

Technical transport limits (not BO policy): subject 1–200, description/message/summary 1–4000, reason 1–2000, optional feedback up to 2000 characters; max 10 distinct, non-null file UUIDs. No raw URLs. Customer cannot submit priority, visibility, arbitrary actor/assignee or result status.

Example creation without a link:

```json
{"subject":"Access question","description":"Please help","facilityId":"<active-facility-uuid>","evidenceFileIds":[]}
```

Example with an owned record (facility is derived; if supplied it must match):

```json
{"subject":"Rental question","description":"Please check this record","linkedRecord":{"type":"RENTAL","id":"<owned-rental-uuid>"}}
```

Link types: `RENTAL`, `RESERVATION`, `PAYMENT`, `STORAGE_UNIT`. Payment ownership follows its Reservation's Customer, not the payment initiator alone. Unit links require a Rental association with the Customer, not knowledge of a unit UUID. No link requires an active facility ID.

Assignment: `{"assignedStaffId":"<eligible-uuid>","expectedVersion":0,"reason":"Shift allocation"}`. The version above is illustrative; always use the actual current value.

Accept: `{"expectedVersion":1}`. Staff message: `{"body":"Progress update","visibility":"PUBLIC","evidenceFileIds":[]}`. Customer message: `{"body":"Requested details","expectedVersion":2}`. Request information: `{"message":"Please provide details","expectedVersion":2}`. Resolution: `{"summary":"Outcome and explanation","expectedVersion":3}`. Reopen: `{"reason":"Issue persists","expectedVersion":4}`. Close: `{"expectedVersion":4,"feedback":"Confirmed"}`.

## Lifecycle

- New ticket: `open`, unassigned Manager queue. Manager assignment still leaves `open` until Staff accepts.
- Reassign nonterminal only: new Staff ID, required reason, increment revision, clear acceptance, preserve immutable events/messages, state `open`.
- Staff must accept current assignment before messaging/processing/resolving. No Manager completion-as-Staff path.
- `request-information` creates a PUBLIC question and enters `waiting_customer`; an INTERNAL note does not change state or notify Customer. A versioned Customer PUBLIC reply resumes `in_progress`.
- PUBLIC Staff replies notify Customer and record first/last public reply timestamps. Internal notes do neither.
- Resolve requires current accepted Staff, `in_progress`, summary, verified linked-objective result if applicable, and no unresolved escalation. Generic unlinked informational tickets may be resolved with a Staff explanation.
- A reply to `resolved` does not silently reopen. Explicit reopen returns to eligible accepted Staff (`in_progress`) or clears ineligible assignment and returns to Manager queue (`open`).
- `closed` cannot be replied to/reopened/reassigned. Customer creates a same-facility linked follow-up; no internal content or files are cloned.
- Closing a ticket does not clear debt, mark a refund paid, release a unit, close a Return case or complete another module's task.

## Shared sources / fail-closed integration

Extension interfaces live in `SupportSources`; **no fabricated runtime implementations are installed**. Test adapters are confined to `src/test`.

| Shared source | Integration / behavior when absent |
| --- | --- |
| EvidenceSource | Must implement current attach/read authorization and atomic binding of file ownership/visibility in the same transaction or a durable outbox. Missing/non-atomic source: nonempty attachment commands return 409 `DEFERRED_SOURCE`; text-only commands remain usable. Missing read source: return UNKNOWN and no file IDs. Continue using the shared download authorization, not a new endpoint. |
| SlaSource | BO priority/policy/version + calendar-derived first reply/next daily update deadlines. Missing: `slaCompleteness=UNKNOWN`, SLA null, explicit missing reason. Never infer zero/healthy or let Customer set priority. Processing pause must reflect `waiting_customer` only. |
| ClosePolicySource | BO policy/version, Customer-close permission and authoritative auto-close deadline. Missing: close/auto-close blocked. Deadline cannot precede resolved+7 calendar days. |
| ResolutionSource | Verify actual linked objective outcome. Missing: linked ticket resolution and final close blocked; note/refund-pending does not count as result. |
| EscalationSource | Must advertise supported modules + transactional/durable routing. Missing: reject escalation before creating an unresolved dead end. Trusted ACK/result is read from receiver and must match ticket/escalation/receiver refs. |

Approved SLA target: HIGH 1 / MEDIUM 4 / LOW 8 working hours for first response; unresolved daily working-day updates. `waiting_customer` pauses active processing, not historical first response/update obligations. Internal waiting is not Customer waiting. Adapter owns calendar arithmetic and historical pause accounting; raw wall-clock fallback is prohibited. Core stores assignment/reply timestamps and event history for this integration. Missing-source mode does **not** claim that SLA monitoring/alerts are live.

System-only `SupportService.autoClose(id)` locks and rechecks resolved state, 7-day minimum review deadline, trusted linked/escalation results, then writes notification + closure + audit in one transaction. Resolution notification is created in the resolution transaction. A reopened ticket is not subsequently auto-closed. The notification used here is the existing in-app notification service, not proof of email delivery.

`SupportAutoCloseCoordinator` is opt-in only (`app.support.auto-close.enabled=true`; absent/false means no bean/job). It scans verified resolved metadata in bounded keyset batches, with an independent locked transaction per ticket; unavailable sources never imply successful closure and do not starve later tickets. Settings: batch-size default100, allowed1..500; initial-delay-ms/fixed-delay-ms default60000. No property was enabled or added to shared configuration. The policy/calendar/schema owner must supply the source and approve activation/scan limits before use. Multi-instance races are handled by ticket locks and resolved-state recheck, not by assuming a single scheduler instance.

## Escalation

Request: `{"targetModule":"PAYMENT","reason":"Owner verification needed","expectedVersion":3}`.

Allowed modules: PAYMENT, RETURN_SETTLEMENT, MAINTENANCE, HANDOVER, ACCOUNT, RENEWAL, OVERDUE. Receiver routing uses module authorization/scope, never developer names or arbitrary recipient IDs. Evidence is INTERNAL.

Manager decision: `{"action":"ROUTE","reason":"Route to authorized owner","expectedVersion":4}` (or REJECT).

Persisted coordination statuses: REQUESTED, ROUTED, REJECTED. ROUTED means a durable receiving/outbox reference, **not** ACK or successful work. Verified receiver states are separately projected as ACKNOWLEDGED, REJECTED, COMPLETED; COMPLETED requires a real result reference. Outstanding/unknown receiver result blocks resolution. Manager may reject coordination with reason without manufacturing owner completion. No direct changes to module-owned records.

## Query / response semantics

List query: page (0-based, default 0), size (default20, max100), status (existing lowercase enum), search (subject/description, trimmed max200, literal wildcard escaping), sort (`createdAt|updatedAt|id|subject,asc|desc`, default createdAt,desc). Manager adds facilityId/staffId. Scope/ownership/filter precede count and pagination; ID is a stable tie-breaker. Timeline routes accept only page/size and order by timestamp then ID. Detail has no query parameters. No priority/SLA filter is advertised without authoritative data.

Ticket detail returns IDs, business status, subject/description, assignment/acceptance/resolution/closure timestamps, version, assignmentRevision, follow-up/link references, workflowReady and explicit SLA completeness. Entities and raw access credentials are not serialized. Internal events/escalations are separate staff/manager-only endpoints. Message file IDs require read authorization.

Errors: 400 invalid input/query/header, 401 unauthenticated, 403 role/permission/scope denial, 404 unavailable/foreign object, 409 stale version, wrong lifecycle, key conflict, inconsistent authoritative data or `DEFERRED_SOURCE`. Retry 409 only after understanding the reason; never assume missing-source is temporary success.

## Persistence / rollout

Five additive tables: support_workflow_states, support_messages, support_workflow_events, support_escalations, support_command_receipts. No shared table ALTER, record delete/rewrite, automatic legacy migration or policy seed. Review-only DDL: `docs/sql/support-workflow-schema.sql`.

Current app uses Hibernate `ddl-auto=update`, which may create these tables on startup. Do not start this build against shared/team DB until schema and permission owners review. H2 tests create a separate disposable schema; this is not MySQL/TiDB migration verification.

Swagger: existing `/swagger-ui/index.html`, `/v3/api-docs`; tags `D5 - Customer Support`, `D5 - Manager Support`, `D5 - Assigned Staff Support`. Use real role tokens/fixture ownership and latest versions; missing-source guards are expected 409, not bypass instructions.
