# notes/ — decision records

**`notes/` is *why*.** `AGENTS.md` carries the rule that binds today; an
Issue carries what's left. A record is written once and then only amended
with a banner — it is never quietly rewritten.

**Read newest-first.** The 2026-08-06 records supersede parts of nearly every
earlier note — including each other — and every affected note carries a banner
saying what changed.

**Adding one**: `YYYY-MM-DD-{decision,design,review}-<slug>.md`. A
`decision` settles something; a `design` works a problem through; a `review`
examines what already exists. Link it from this index in the same change, and
reflect it in the right `AGENTS.md` **only if it changes a standing rule** —
see that file's `Rules` section for the admission test.

## Product & scope

| Record | What it settles |
|---|---|
| `2026-08-19-decision-launch-date-and-google-only.md` | **Launch is 2026-08-31.** v1 ships on Google alone; the "does this block v1?" gate |
| `2026-08-11-decision-deletion-and-infant-meals.md` | 유아식 does not adjust 보증인원; deletion cannot trigger `GuestChange` retention |
| `2026-08-11-decision-import-row-rejection.md` | A bad 관계 rejects the row, not the file — supersedes the synonym table |
| `2026-08-06-decision-v1-scope-and-meals.md` | The v1 cut; meal types per Wedding |
| `2026-08-06-decision-drop-response-model.md` | No `RsvpResponse` / `ResponseMatch` in v1 |
| `2026-08-06-design-ledger-and-import.md` | Ledger + headcount are one screen; the import workflow |
| `2026-08-06-review-scale-and-extensibility.md` | What the v1 shape costs later |
| `2026-08-05-design-meal-headcount.md` | Headcount aggregation |
| `2026-07-30-design-guest-ledger-hard-spots.md` | The MVP's hard design spots |
| `2026-07-26-decision-core-scope.md` | Core scope |
| `2026-07-26-mvp-v1-requirements.md` | The v1 requirement list |
| `2026-07-26-founder-interview.md` | Origin — the problem in the founder's words |

## Domain & data

| Record | What it settles |
|---|---|
| `2026-08-11-decision-baseline-schema-calls.md` | The v1 baseline schema's contested calls; verified-email merge key; integrity `wedding_id` |
| `2026-08-10-decision-soft-delete.md` | Every user-deletable row is soft-deleted |
| `2026-08-07-decision-import-idempotency.md` | File hash; a returning file is a stale name list |
| `2026-08-03-design-domain-model.md` | The domain model |

## Backend (`api/`)

| Record | What it settles |
|---|---|
| `2026-08-20-decision-the-ledger-read-and-its-filters.md` | 원장 목록은 페이지를 나누지 않는다; 입력 순서가 계약이고, 참석 필터는 인원수가 세는 값을 읽는다 |
| `2026-08-20-decision-row-concurrency-and-the-audit-trail.md` | 같은 컬럼 경쟁은 마지막 쓰기가 이기되 덮인 값은 `guest_change`에 남는다; `@Version` 없음, 상대적 변경 금지 |
| `2026-08-20-decision-listing-the-callers-weddings.md` | `GET /weddings` answers 최초 1회 from the session; a scopeless wedding endpoint is named in an allowlist, never inferred from the path |
| `2026-08-19-decision-wedding-scope-gate.md` | The gate fails closed by a build-time sweep, not an interceptor; resolution filters `wedding.deleted_at`; a refusal is marked in the log only |
| `2026-08-13-decision-first-login-idempotency-and-email-merge.md` | First login is idempotent per identity; only a verified address merges, and we can be the verifier |
| `2026-08-13-decision-login-failure-return-path.md` | A failed callback returns to the front with a closed code in the fragment; the copy is `web/`'s |
| `2026-08-12-decision-login-slice-by-provider.md` | Login splits by provider; the email merge stays with Google |
| `2026-08-17-decision-first-domain-endpoint-shape.md` | The pattern fifteen endpoints copy; where the `CurrentWedding` resolver may live; a validation bound is the column's |
| `2026-08-17-decision-log-masking-mechanism.md` | Masking is the driver's switch and four pinned loggers, not a message scrubber — and a cast is not a validator |
| `2026-08-17-decision-pre-auth-rate-limit-and-session-retention.md` | The pre-auth limit is the edge's and names both OAuth paths; a dead session row keeps 90 days past expiry |
| `2026-08-12-decision-session-token-shape.md` | The session token is `selector.verifier` — for a testable comparison, not a closed hole |
| `2026-08-12-decision-cors.md` | CORS denies by default, exact origins only, patterns forbidden at the type level |
| `2026-08-12-decision-session-lifetimes.md` | Session idle 30 days, absolute 180 — the couple open this a few times a month |
| `2026-08-12-decision-session-cookie-ambiguity.md` | Two session cookies: refuse the read, revoke both |
| `2026-08-12-decision-auth-package-structure.md` | `auth/` splits into account · oauth · session; the boundary and the layer direction are tests, not `internal` |
| `2026-08-10-decision-auth-gate-and-sequence.md` | The resolver is the gate, not the filter chain; the build order |
| `2026-08-10-decision-cross-tenant-status-code.md` | Cross-tenant is 404, never 403 |
| `2026-08-09-decision-schema-ownership.md` | Flyway in tests only; DDL applied by hand |
| `2026-08-07-decision-backend-api-conventions.md` | No envelope; RFC 9457 + `code`; DTO naming |
| `2026-08-07-decision-backend-architecture.md` | Domain-based packages; shallow layers |
| `2026-08-07-decision-backend-tdd-methodology.md` | Red / Blue / Green, per requirement |

## Frontend (`web/`) & design

| Record | What it settles |
|---|---|
| `2026-08-21-decision-query-defaults-and-mutation-ordering.md` | The QueryClient's defaults; out-of-order mutation responses are prevented by serialising, not reconciled |
| `2026-08-19-decision-generated-api-types.md` | The generated types are committed; a `seam` CI job refuses a stale one; what stays hand-written |
| `2026-08-13-decision-frontend-routing.md` | Plain `<Routes>`, no loaders; the session gate sits above the table |
| `2026-08-10-decision-design-value-enforcement.md` | Our own checker, not the linter |
| `2026-08-08-decision-frontend-architecture.md` | Flat until it scatters; React Query; the token bridge |
| `2026-08-08-decision-frontend-testing-methodology.md` | Integration-first; where tests are mandatory |
| `2026-08-07-design-system.md` | 백자 · 금박 / 나전칠기; tokens, faces, the component inventory |
| `2026-08-07-design-screens-and-flow.md` | The screens and the flow between them |

## Process & infrastructure

| Record | What it settles |
|---|---|
| `2026-08-13-decision-drop-the-stop-pipeline.md` | **No development ritual.** What was removed, and what it cost |
| `2026-08-13-decision-static-front-and-content-type-gate.md` | The front ships static; no endpoint accepts a CORS-safelisted content type, and CI sweeps for it |
| `2026-08-19-decision-openapi-artifact.md` | The seam's document is a test artifact at `api/build/openapi.json`; a test-source controller is a `@TestComponent` or it is published |
| `2026-08-08-decision-development-tempo.md` | *Superseded* — vertical slices; one stop; the comprehension gate |
| `2026-08-08-decision-work-tracking.md` | GitHub Issues via `gh`; two milestones, four labels |
| `2026-08-20-decision-guest-entry-side-and-companions.md` | 측은 기본값을 갖지 않는다; 동반인원은 대표자를 따르므로 행이 아니라 숫자다 |
| `2026-08-20-decision-mutation-response-envelope.md` | 뮤테이션 응답은 `{resource, headcount}` — 봉투 금지 규칙이 좁혀진다 |
| `2026-08-20-decision-merge-order-gate.md` | 두 PR이 따로는 초록, 합치면 빨강 — 무엇을 사고 무엇을 안 샀나 |
| `2026-08-08-decision-build-workflow.md` | *Partly superseded* — the CI jobs; never merge a red check |
| `2026-08-11-decision-claude-setup.md` | Where a behaviour lives — skill vs hook vs prose; the prompt cleanup |
| `2026-08-11-decision-agents-md-hierarchy.md` | AGENTS.md becomes a hierarchy, and gains an eviction rule |
| `2026-08-08-decision-merge-gate.md` | The local gate, and why it is leaky on purpose |
| `2026-08-08-decision-scaffold-secrets-and-surface.md` | A secret never travels in a connection string |
| `2026-07-30-decision-network-security.md` | The security posture |
| `2026-07-30-decision-tech-stack.md` | The stack |
| `2026-07-30-decision-client-strategy.md` | Token from the request; all computation server-side |
