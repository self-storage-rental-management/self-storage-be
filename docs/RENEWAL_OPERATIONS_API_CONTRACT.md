# Renewal Operations API — D3

Implementation date: 08/10/2026. Base `/api`, bearer JWT, standard ApiResponse/PageResponse. This is an additive backend implementation, not a declaration that cross-role rollout is ready. Read with RENEWAL_API_CONTRACT.md (D2).

## Shared integration gates

No runtime implementations/defaults are installed for the ports in `service/renewal/operations/RenewalOperationSources.java`.

| Port | Owner-supplied guarantees | Missing behavior |
|---|---|---|
| AuthorizationSource | Actual signing/CASH/exception/refund permission, current Staff assignment, OPERATE/MANAGE scope, assigned IDs for queue before pagination | Related Staff/specialized Manager operation409 DEFERRED_SOURCE; never reuse PERFORM_CHECKIN |
| PolicySource | BO signing window, bounded exception extension, policy ref/version, authoritative facility-fault classification | No deposit/exception decision using hardcoded policy |
| CalendarSource | Real office hours, holidays, complete slot duration, feasibility; transactional slot reserve/replacement for RENEWAL purpose | No appointment promise; no Check-in changes |
| SafetySource | Shared Rental/hold/Booking/Assignment/Return/Recovery/maintenance lock protocol; transactional retain/reacquire/consume/release | No capacity commitment or completion |
| AccountingSource | Authoritative Renewal deposit linkage and attempts, resource-bound idempotency, real payable statements/CASH receipts/allocations, required payment set, in-flight expiry reconciliation | No paid/zero-debt inference; no Booking40% substitution |
| EvidenceSource | Actual FileAsset refs, uploader/link/resource/purpose/visibility checks | No arbitrary URL or cross-resource proof accepted |
| RefundSource | BO entitlement, actual paid/refunded/pending reservation accounting, business-calendar SLA, transactional reservation | No approval/payout success; approval only awaits shared execution |

`atomic()` defaults false. Opt-in requires real local transaction/rollback and shared-writer consistency; a flag alone is not sufficient. All source failures must roll back local and shared changes. Notification/receipt/refund execution belongs to shared owners; no imaginary HTTP URLs or duplicate money ledger are created.

## Routes

| Actor | Method/path suffix | Body | Result |
|---|---|---|---|
| Customer | GET `/customer/renewals/{id}/operations` | — | Operational state, verified workflow expectedVersion, missingSources |
| Customer | POST `.../simulated-payment` | expectedVersion |200 Result; DEPOSIT only, server terms/outcome |
| Customer | POST/PATCH `.../appointment` | appointmentAt ISO instant, reason (mandatory PATCH), expectedVersion |200 Result; entire real slot inside effective deadline |
| Customer | GET `.../appointment` | — | State with current appointmentRef/start/end/deadlines |
| Customer | POST `.../exception-confirmations` | decisionRef, expectedVersion |200 Result; latest proposal only, revalidate policy/fault/calendar/hold/cutoff |
| Customer/Manager/Staff | GET `/{role}/renewals/{id}/payments` | page,size | Persisted D3 deposit/CASH event timeline, not imported legacy payment history |
| Customer/Manager | GET `/{role}/renewals/{id}/refunds` | page,size | Refund decision/reservation history, not payout proof; Customer internal evidence stripped |
| Staff | GET `/staff/renewals/{id}` | — | Assigned signing state only |
| Staff | GET `/staff/renewal-appointments` | page,size,facilityId,date ISO YYYY-MM-DD,status SIGNING/SIGNING_EXPIRED/COMPLETED | Assignment and OPERATE scope filter BEFORE pagination; sort appointmentStart/id |
| Staff | POST `/staff/renewals/{id}/arrival` | appointmentRef,evidenceFileIds,expectedVersion |201 server-clock event; no client arrivalAt/backdate |
| Staff | POST `.../facility-incidents` | appointmentRef,reason,evidenceFileIds,expectedVersion |201 event; incident can be recorded after expiry for review |
| Staff | GET `.../payable-statement` | — | Actual statementRef/version/expiry/amount/required obligations |
| Staff | POST `.../cash-receipts` | payableStatementRef,receiptReference,received=true,expectedVersion |201 actual shared receipt event; not completion |
| Staff | POST `.../completion` | expectedVersion,identityVerified=true,arrivalRef,signedDocumentFileId,completionNote |200 Result; extend Rental exactly once |
| Manager | GET `/manager/renewals/{id}/operations` | — | READ + VIEW_RENTALS scoped state |
| Manager | GET `.../facility-incidents` | page,size | Internal incident/exception timeline |
| Manager | POST `.../exception-decisions` | incidentId,action,appointmentAt/revisedDeadline for reschedule,reason,evidenceFileIds,expectedVersion |201 proposal; specialized permission required |
| Manager | POST `.../refund-decisions` | incidentId,decision APPROVE/REJECT,reason,evidenceFileIds,expectedVersion |201 actual entitlement reservation or rejection; never payout |

Mutation header `Idempotency-Key` required, 1–100 characters. DTO unknown fields rejected. reason/note max2000, evidence list max10 unique non-null FileAsset IDs. No arbitrary amount, outcome, actor, currency, paidAt, overrideDeadline or exception=true. Page0,size20 max100; unknown/repeated filters400; GET no mutation.

Result = `{state: RenewalOperationResponse, event: {id,kind,occurredAt,actorId,data}}`; wrapped in ApiResponse. `state.expectedVersion` is the SAME verified D2 workflow version, not the supplemental state JPA version. Every successful operation forces workflow version increment. 201 retry stays201 and returns original persisted result. Current role/resource/scope/assignment authorization BEFORE replay; fresh state/version/deadline guards only for new keys.

## Lifecycle and safety

- Approved terms remain immutable. Deposit amount comes from customer-accepted quote, not live catalog or Manager values. Shared engine records SUCCESS/FAILED/NOT_RECEIVED; failed attempts do not overwrite approved/signing business state.
- Before deposit: approval/payment deadline/cutoff, hold and calendar feasibility. Verify paidAt against server clock AFTER engine call, amount/currency/link, and deadlines. Signing deadline derives from shared signing duration and recovery cutoff; deposit does not extend Rental.
- Separate `renewal_operation_states` tracks SIGNING, SIGNING_EXPIRED, PAYMENT_EXPIRED, COMPLETED. Never rename/add shared RenewalStatus. Paid signing expiry does NOT become payment_expired or automatic customer fault/forfeiture.
- Read state can show SIGNING_EXPIRY_PENDING when time passed but reconciliation has not run; GET does not execute expiry. A verified approved request without D3 row shows AWAITING_DEPOSIT, legacy unknown remains UNKNOWN/null.
- Appointment replaces current appointment ref and resets current arrival only before arrival; after arrival, facility incident review needed. Calendar reserves real slot transactionally, purpose RENEWAL, not Check-in.
- Arrival is real server event tied to current appointment; no endDate/deadline changes. Signed evidence and fully-paid financial set still mandatory at completion.
- Normal completion locks actor User -> Rental -> D2 workflow, rechecks active/no-return/allocation/end snapshot, evidence, full required payment set and shared safety, then consumes hold, sets Rental end exactly to accepted newEndDate, marks completed and releases ONLY matching renewal open slot. Physical unit/access never released.
- Completion allowed at equality to effective deadline/cutoff; after blocked. Source shared lock guarantees are required for Return/Recovery races, not proved by local Rental lock alone.
- Exception APPROVE_RESCHEDULE_BEFORE_CUTOFF is a proposal only. It needs verified facility fault, actual slot and BO extension bound relative to ORIGINAL deadline. Latest proposal tracked explicitly; Customer confirmation rechecks and reacquires/retains hold. Original deadline retained; terminal requests cannot revive, postcutoff cannot complete/pause Recovery.
- REJECT and REQUEST_POST_CUTOFF_REVIEW record review only, no deadline or Rental status override. Additional specialized authorization required.
- Refund approval reserves actual refundable entitlement via owner and returns APPROVED_AWAITING_EXECUTION; pending amounts must prevent duplicate approval under shared lock. No ReturnCase/security-deposit substitution, negative payment, wallet or simulated actual transfer. Shared executor must be authorized assigned Staff, not approver, with actual proof/history/idempotency; executor API is NOT created by this module.
- `RenewalOperationService.expire(id)` is an INTERNAL transactional reconciliation entry point. It checks server clock strictly after deadline, reconciles in-flight payments, releases extension hold only and records REVIEW_REQUIRED. Paid history, original dates, open slot and physical unit preserved. No automatic scheduler enabled until source/lifecycle/schema owners approve rollout.

## Persistence/rollout

Additive tables: renewal_operation_states, renewal_operation_events; immutable events carry actual actor/server occurredAt and parent renewal. D2 renewal_idempotency is reused with distinct operation namespace (`d3_*`); no second cache. D3 commands do not fabricate versions for legacy rows.

Review-only DDL in docs/sql/renewal-operations-overdue-schema.sql. Not a Flyway migration, not executed. Existing ddl-auto=update may create tables on startup; DO NOT start against team TiDB/MySQL before coordinated schema approval. No historical backfill or record repair.

400 malformed/injected fields/missing header;401 invalid session;403 role/capability;404 invisible resource/nested refs;409 stale version/phase/deadline/conflict/DEFERRED_SOURCE. Existing global codes/envelopes remain unchanged.

Full production acceptance still requires actual adapters, owner-reviewed schema/permissions, expiry/open-slot reconciliation, shared refund execution/reconciliation visibility, notification integration and MySQL/cross-writer E2E. Local test adapters are fixtures only.
