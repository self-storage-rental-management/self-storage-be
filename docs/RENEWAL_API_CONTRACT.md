# Renewal API (D2)

## Additive completion patch — 06/10/2026

- Renewal responses add nullable `acceptedTerms` (nested `RenewalQuoteResponse.Terms`, not flattened) and `cancellationReason`. Terms come only from persisted customer-accepted revision/quote; no current-price recalculation. Legacy without workflow returns null. List/detail/command schemas are consistent; no separate public quote lookup is introduced.
- Projection checks accepted quote Rental/customer binding and persisted request amount/newEndDate consistency. Corruption yields409 without repairing records or exposing unrelated resources.
- Cancellation reason is the existing persisted workflow reason. No cancelledBy/cancelledAt is inferred from requestedBy/reviewer/updatedAt; those fields are not added without authoritative evidence.
- `FinancialSource.consistentThroughApproval()` defaults false. An owner must implement a shared lock/version consistency protocol before opting in; checkedAt alone is not a concurrency guarantee. Read projections may show COMPLETE while approval remains blocked due to missing consistency protocol.
- Optional `ApprovalLifecycleSource.ready(Rental)` is required for APPROVE and APPROVE allowedActions, in addition to policy/pricing/eligibility/complete finance/atomic hold. No runtime implementation is installed. Owner readiness means payment linkage, deadline/expiry reconciliation and matching hold/open-slot handoff have actually been implemented; it is not a frontend/environment toggle.
- Missing approval dependencies appear in disabledReasons; rejection and verified pending cancellation remain independent. Approval does not extend Rental dates. Already-approved records are not rewritten by this patch.
- User-facing accepted price/deposit/remainder are obligations, not evidence of payment. FE displays historical accepted terms and preserves honest null/UNKNOWN states.

Shared policy publication, pricing/package eligibility/rounding, date/calendar/Recovery sources, finance, cross-writer hold protocol, D3 lifecycle and owner-approved MySQL migration remain external integration dependencies. This patch does not choose an expired-request lifecycle or rounding policy for other owners.

Base `/api`; bearer JWT; standard ApiResponse/PageResponse/correlationId. No runtime seed/default policy, fake financial source or imaginary HTTP endpoint.

## Operations

| Method/path | Result | Prerequisites |
|---|---|---|
| GET /customer/rentals/{id}/renewal-options |200 eligible options | Owned active Rental, policy/eligibility/pricing |
| POST /customer/rentals/{id}/renewal-quote |200 immutable quote | pricingPackageCode; authoritative sources; no hold/extension |
| POST /customer/rentals/{id}/renewal-requests |201 Renewal | renewalQuoteId,note; Idempotency-Key; unexpired owned quote, eligibility, one open request |
| PATCH /customer/renewals/{id} |200 revision | renewalQuoteId,note,expectedVersion; Idempotency-Key; pending |
| POST /customer/renewals/{id}/cancel |200 cancelled | reason,expectedVersion; Idempotency-Key; verified pending workflow; no policy required |
| GET /{customer,manager}/renewals and /{id} |200 scoped records | Customer ownership; Manager VIEW_RENTALS + READ |
| POST /manager/renewals/{id}/decision |200 decision | APPROVE/REJECT,reason,expectedVersion; Idempotency-Key; MANAGE_RENTALS + MANAGE |

REJECT requires reason, not policy/finance/hold. APPROVE revalidates active Rental, dates/physical allocation, pricing/policy, due obligations/disputes and atomic hold. Approval does not extend Rental or change its old rate/unit state. Financial UNKNOWN blocks approval, not request creation. Approved/payment cancellation and completion require D3 coordination, outside this slice.

Commands reject unknown body fields. note/reason max2000; version>=0; key1–100. Reauthorize before replay, bind resource and canonical payload hash, return original status/data without duplicate audit/hold; no cached correlationId. Scoped RenewalRequestAdvice maps missing headers to400 without changing shared handler/other role behavior.

## Queries/projection

page0,size20 max100; status exact enum,rentalId; Manager adds facilityId/search. Search literal case-insensitive unit/customer name or exact Rental/Renewal UUID. Sort createdAt,newEndDate,amount,id; default createdAt desc + id asc. Unknown/repeated query400, detail no query; scope/filter before database pagination.

Quote JSON fields flattened: id,rentalId,customerId,quotedAt,expiresAt plus oldEnd, half-open period/newEnd, physical unit/facility/type, package/version, rate/subtotal/discount/net/deposit/remainder and BO policy ref/version. No PIN/debug/legal contract record. Detail reads actual workflow version/accepted revision/reviewer/time/reason/deadline/hold. Legacy without workflow remains null/UNKNOWN, not fake version0/history.

GET computes reviewState and actor-specific allowedActions without writes. Finance UNKNOWN/null when source incomplete; never zero debt. Changed pricing/policy before approval ->409, new quote + Customer PATCH confirmation. Pending does not expire just because accepted quote TTL elapsed. Non-active or unavailable sources are not reported READY.

## Required authoritative sources (no runtime implementations here)

PolicySource: BO reference/version, TTL, payment/request window, deposit rate, eligible package IDs. PricingSource: effective package/rate/discount/version and unit-type mapping. Calculator is VND scale2 HALF_UP: adapter must explicitly publish moneyScale2; scale0/other currencies blocked, not converted. Owner must confirm rounding publication.

EligibilitySource: verified date provenance/recovery cutoff/feasibility. FinancialSource: due obligation refs/disputes with checkedAt; authoritative read freshness is adapter responsibility. ExtensionHoldSource: occupied-extension shared capacity/lock protocol; default participatesInTransaction=false blocks approval. Reviewed local transactional implementation must opt in and guarantee rollback together with caller; no remote non-atomic side effect. Test implementations exist only in H2 fixtures.

## Persistence/transaction

Additive renewal_quotes, renewal_accepted_revisions, renewal_workflows, renewal_open_slots, renewal_idempotency. Existing shared Rental/Renewal/ReservationQuote structure unchanged. Immutable snapshot/revisions, unique accepted quote/revision, @Version workflow. No deletion of history.

READ_COMMITTED commands; lock order actor User -> Rental -> workflow -> shared hold; quote only locks Rental. Actor lock serializes same-actor idempotency; Rental lock + slot PK guard one open request. Recheck ownership/allocation/version after locking. Legacy unresolved requests also block submission. Accepted quote cannot be reused after cancellation. Only rejected/cancelled/completed release matching slot; payment_expired not guessed terminal.

Idempotency uniqueness actor/operation/SHA-256(exact UTF-8 key) avoids MySQL collation case/padding ambiguity. Canonical hash sorts object keys recursively, preserves arrays; result data preserves monetary representation. No purge.

Audit reuses existing AuditLogService with real actor in transaction. Hold/deadline/status/audit/idempotency commit together; failure rolls back. Local locking does not prove Booking/Return/Assignment capacity safety; shared owner lock protocol and MySQL regression still required.

## Rollout/errors

docs/sql/renewal-persistence-schema.sql is review-only DDL, not enabled Flyway. No runtime MySQL migration/data change in this task. Existing dev ddl-auto=update can create tables on startup: do not start against team/shared MySQL before owner review. Staging/prod validate need approved migration; no legacy backfill.

400 invalid/unknown fields/query/header;401 invalid session;403 missing grant;404 invisible resource/quote;409 stale terms/version, duplicate-open, expiry, phase/move-out/debt/capacity/source conflict. Missing source uses existing CONFLICT + DEFERRED_SOURCE message; no global enum change. Full cross-role D2 rollout needs actual sources, schema/lock approval, D3 coordination and MySQL/E2E verification.
