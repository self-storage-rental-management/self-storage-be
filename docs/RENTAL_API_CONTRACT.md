# Rental Read API Contract

Implemented scope: four GET endpoints only. No activation, payments, assignment, renewal decisions or legal-contract generation.

## Swagger

Run BE with your existing local configuration, then open `/swagger-ui/index.html` on the BE origin (default Spring port8080 unless your configuration overrides it). Existing `/v3/api-docs` and bearerAuth are reused. Tags: **D1 - Customer Rentals**, **D1 - Manager Rentals**. Login using existing Auth API, copy accessToken to **Authorize**, then Try it out. Do not publish tokens or use production/staging credentials in test logs.

- GET /api/customer/rentals and /api/customer/rentals/{id}: CUSTOMER role + owner from JWT. Existing customer reservation API uses ownership without a seeded Customer VIEW_RENTALS grant; D1 follows that pattern without changing auth.
- GET /api/manager/rentals and /api/manager/rentals/{id}: MANAGER + VIEW_RENTALS + READ facility scopes. No automatic permission grant. Missing grants/scope produce403; authorized actor querying invisible detail receives404.

List: page=0,size=20 (1–100), canonical status, search(max200 trimmed), endFrom/endTo inclusive ISO dates, sort=createdAt,desc default. Sort fields: createdAt,contractEndDate,startDate,monthlyPrice,id; asc/desc; id ASC tie-break. Manager facilityId optional; omitted means union of READ scopes. Search is literal case-insensitive unit-code substring or exact Rental UUID; Manager additionally searches customer fullName. No cross-owner search.

Unknown/repeated parameters (including needsAttention) return400. Detail accepts no query. Pagination/envelopes reuse common classes. Price from Rental.monthlyPrice; UnitType via StorageUnit; missing financial/access always UNKNOWN/null, not PAID/zero. No raw PIN. Core relationship mismatch returns409 without leaking related record IDs. Reads never repair shared data.

## Integration gates

Rental activation/applied-rate handoff and legacy date semantics require verified integration sources. The API returns stored dates with an explicit warning because no record-level provenance source currently exists; it never backfills dates. Manager role grants/scopes must be configured through the existing authorization workflow. No seed/business records are created for Swagger. An empty authorized query does not prove Booking→Rental activation works. Financial/access integrations and obligations/needsAttention are deferred; unit tests alone do not complete runtime E2E acceptance.

## Manual checks

1. Customer sees only own rentals; another Customer ID detail404.
2. Manager with VIEW_RENTALS and READ scope sees only those facilities; omitted facilityId covers all authorized facilities.
3. Missing permission/no scopes403; no token401; outside-scope detail404.
4. size101, unknown status, repeated query, invalid/reversed dates or needsAttention400.
5. VND applied rate unchanged by catalog updates; correct UnitType; no PIN, financial UNKNOWN.
6. Pagination totals/filter/sort stable; GET never writes; core mismatch409.

No runtime accounts, fake IDs or API fallback supplied. Use real local records; test fixtures live only in tests.

## Automated tests

Run `mvn -Dtest=RentalQueryTests,RentalQueryServiceTests,RentalReadIntegrationTests,RentalSwaggerTests,CancelledReservationUnitReleaseServiceTests test` with the test profile/H2 fixtures provided by the tests. RentalSwaggerTests uses the shared production Security configuration without a test-only decoder selector. Runtime acceptance additionally requires actual permissions/scopes, activation handoff and database integration verification.
