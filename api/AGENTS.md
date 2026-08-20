# api/ — backend rules

Kotlin + Spring Boot, JSON API only. **The root `AGENTS.md` still binds** —
this file adds what only applies inside `api/`, and never contradicts it.
`notes/` remains the single source of truth for *why*; every section below
names its record.

- JDK 21, Gradle KTS. Spring Data JPA + Flyway (**tests only**),
  **PostgreSQL 16** (ratified 2026-08-08 — the native aggregation queries are
  where a version difference yields a different *number* rather than an error).
  JUnit 5 + Testcontainers.
- Two standing client rules (`notes/2026-07-30-decision-client-strategy.md`),
  kept so a native couple app stays possible without paying for it now:
  session lookup reads a token from the **request**, not a cookie, and **all
  computation stays server-side** — the API returns conclusions, not rows to
  compute over.

## Schema ownership (decided 2026-08-09)

`notes/2026-08-09-decision-schema-ownership.md`. **Supersedes** the earlier
"Flyway owns the schema everywhere".

- **Flyway runs in tests only. Every DDL statement against a real database is
  applied by the founder, by hand.** A migration running unattended at startup
  is irreversible work at the moment an environment is least observed.
- **The migration SQL files stay authoritative and stay the only copy.** A
  second copy of the DDL is what would make this unworkable.
- **`ddl-auto: validate` is the drift detector, and its reach is narrower than
  the name suggests**: Hibernate 6 compares mapped columns' JDBC **type codes
  only** — not length, precision, scale or nullability, and nothing about
  indexes, constraints or defaults. A hand-typed `varchar(20)` under a
  `length = 255` mapping passes, then fails at INSERT on a long 하객 이름.
  Size is the likeliest drift when a person types DDL, and it is uncaught.
- **The environment outranks every yml.** `SPRING_FLYWAY_ENABLED=true` in the
  deploy platform reverses this decision with the whole suite green — so the
  resolved values are asserted at **startup**, not only in tests. A guard that
  reads a committed file cannot see the environment that actually runs.
- **Agents may not reach a non-local database.** `.claude/hooks/db-guard.sh`
  refuses any DB client command whose target is not loopback or is
  unresolvable. It blocks the founder's own sessions on purpose. It does
  **not** cover SSH tunnels, unknown clients, script files, non-Bash tools, or
  Codex — it stops the casual path, not a determined one.
- **`prod-boot` rehearses the configuration, never the schema.** It is a Gradle
  `Test` task, so it inherits the Flyway opt-in *even in CI* and builds its own
  schema from the migrations. Only **`docker`** runs the real deploy shape.
  Do not read a green `prod-boot` as a rehearsed deploy.

## Development methodology (decided 2026-08-07)

`notes/2026-08-07-decision-backend-tdd-methodology.md`. Three gates per
**requirement**, not per PR:

1. **Red Gate** — a failing test for the requirement, written first.
2. **Blue Gate** — the minimum implementation that turns it green, nothing the
   test doesn't ask for.
3. **Green Gate** — refactor for stability and extensibility with the suite
   green throughout.

This governs *when* a test is written. *What kind* is unchanged: JUnit 5 +
Testcontainers, mandatory for wedding-scoped queries, aggregation, import,
and tokens.

## Architecture (decided 2026-08-07)

`notes/2026-08-07-decision-backend-architecture.md`.

- **Packages are domain-based** (`wedding/`, `guest/`, `guestimport/`,
  `auth/`), each self-contained with its own Controller/Service/Repository/
  Entity — not layer-based. It is `guestimport/` and not `import/` because
  **`import` is not a legal Kotlin package name here** — it compiles, but
  ktlint's `standard:package-name` rejects it. (`intake` lost because it would
  also plausibly hold `email_ingest`, a separate domain.)
- **Layers stay shallow**: Controller (DTO) → Service (tx boundary — except
  `LoginService`, whose retry needs an insert to fail alone, `#93` —
  invariants, aggregate recompute, `GuestChange` writes) → Repository (JPA +
  native aggregation queries). No hexagonal/ports-and-adapters layer.
- **Split a class when it starts doing two distinct things, not before.** Only
  the Controller and a declared cross-domain contract are `public`. `internal`
  is MODULE-scoped and `api/` is one module, so it enforces no package boundary;
  `ArchitectureTest` and `SourceShapeTest` do
  (`notes/2026-08-12-decision-auth-package-structure.md`).
- **Never a layer bucket** — `AuthRepositories.kt`, `AuthEntities.kt`. The checks
  catch a persistence or configuration type sharing a file, and a misnamed
  single-type file; two ordinary types sharing one is still your judgement.
- **Tests mirror the domain tree**, not the layer tree. Three kinds, chosen by
  where a requirement's risk lives: Service unit tests (no DB), Repository
  Testcontainers tests (mandatory for the paths named above), Controller
  contract tests (against `docs/api-spec.md`).
- **A boot test must be named `*ProfileBootTest`.** The `prod-boot` CI job
  selects by that pattern, so a boot test named anything else silently
  vanishes from that check — and does *not* go red, because the `api` job
  still runs it. The name is a contract, not a style.

## API conventions (decided 2026-08-07)

`notes/2026-08-07-decision-backend-api-conventions.md`.

- **No generic envelope** (`{data: ...}`) — a read returns the resource's own
  DTO directly. But a **mutation on a wedding-scoped resource** returns
  `{resource, headcount}`: ledger and headcount are one screen (2026-08-20).
- **Errors are RFC 9457 Problem Details**, via Spring Boot 3's native
  `ProblemDetail` — `spring.mvc.problemdetails.enabled=true` plus one global
  `@ControllerAdvice`. Extended with a **`code`** field (e.g.
  `GUEST_NOT_FOUND`) so the frontend switches on a stable string instead of
  parsing `detail`.
- **Standard verb/outcome status mapping**: 200 read/update, 201 create, 204
  delete, 400 validation, 404 not found, 409 conflict, 401 unauthenticated,
  500 unhandled (masked per **Security posture**).
- **A cross-tenant request is 404, never 403** (decided 2026-08-10,
  `notes/2026-08-10-decision-cross-tenant-status-code.md`). Any resource
  addressed by a caller-supplied id whose owning wedding the caller is not a
  member of returns the same response as an id that does not exist —
  otherwise the pair is a wedding-id oracle. **403 means the caller *is* a
  member and lacks a privilege within that wedding, so v1 has no correct use
  for it.** 404 is the default and not a per-endpoint exception, for the same
  reason `wedding_id` sits on every root: a default cannot be forgotten, a
  judgement call can.
- **DTO naming**: `XxxRequest` / `XxxResponse`, mapped via extension functions
  in the domain package. Entities never serialize directly. **The Service returns
  the response DTO**, and the DTO owns its file — a Controller that mapped an
  entity itself would be reading associations after the transaction closed, and
  `open-in-view: false` makes that a `LazyInitializationException` in whichever
  domain first has associations, not in the one that set the pattern.
- **No `/v1` prefix** — no second API version planned; free to add later.
- **ktlint** via the standard Gradle plugin, default ruleset.
- **`docs/api-spec.md` is written in the same change as the code** — new,
  changed and deprecated alike, never as a follow-up. It stays authoritative
  for **meaning** (what an endpoint is for, which invariant it protects),
  which OpenAPI cannot carry.

## Security posture (decided 2026-07-30)

`notes/2026-07-30-decision-network-security.md`, plus the amendments named
inline. The parts that constrain everyday backend work:

- **All tokens** (v1: session and invite; later: shared link, per-guest link):
  ≥128-bit CSPRNG, **stored SHA-256-hashed**, constant-time compared, masked
  in logs. Privileges and lifetimes differ per kind — the per-guest link can
  only respond as that guest, never read.
- **Only a provider-*verified* email is an account merge key** (decided
  2026-08-11, `notes/2026-08-11-decision-baseline-schema-calls.md` §A).
  Merging on a raw email is a **full ledger takeover with no token, no expiry
  and no invite** — that is the whole reason for the rule. An unverified
  address is not stored: `app_user.email` stays NULL, `email_verified_by`
  records whose word we took, and a CHECK binds the two. **v1 has no
  account-linking flow**, so a second account simply stands alone. Three
  constraints hold it, and none may be relaxed: **`email_verified_by in
  ('GOOGLE', 'KAKAO')`** (Naver's address is user-editable and asserts nothing,
  so `'NAVER'` can never be true); **a shape CHECK** (`like '%_@_%'`, no
  whitespace), since a stored `''` is one `app_user` shared by every stranger
  whose provider returned an empty email; and **a unique index on
  `lower(email collate "C")`**.
  **`#82` is the matching obligation on `#37`**: an index that forbids the
  duplicate does not make the lookup find it. The lookup must use the *same*
  expression, and the app-side normalisation must be an **ASCII-only**
  lowercase — Kotlin's `String.lowercase()` is not `lower(... collate "C")`.
- **A session token is read through `SessionTokens` and nowhere else**, and its
  two functions are deliberately asymmetric: reading refuses a request carrying
  more than one cookie, revoking ends all of them (decided 2026-08-12,
  `notes/2026-08-12-decision-session-cookie-ambiguity.md`). Making them consistent
  turns a denial of service into session fixation — the logout path did exactly
  that before review caught it.
- **The auth gate is our resolver, not Spring Security's filter chain**
  (decided 2026-08-10, `notes/2026-08-10-decision-auth-gate-and-sequence.md`).
  `authorizeHttpRequests` stays `permitAll` in **every** environment; what
  rejects a request is `user → membership → wedding` resolution failing. The
  asymmetry is retrofit cost: flipping the filter chain later is one line,
  whereas retrofitting the resolver means threading a parameter through every
  endpoint written in the meantime, by hand and silently. This is a design,
  not deferred hardening, and it is only honest because two tests hold it: an
  anonymous request to a wedding-scoped endpoint is 401, an authenticated
  non-member is 404. **Forgetting the resolver fails closed** — `ResolvedPrincipalTest`
  refuses a handler declaring neither principal, one under `{weddingId}` without
  `WeddingScope`, and any principal the REQUEST supplies (decided 2026-08-19).
- **CSRF in v1 is the CORS preflight, not `SameSite=Lax`** (narrowed 2026-08-13,
  `notes/2026-08-13-decision-static-front-and-content-type-gate.md`). `SameSite`
  is a *site* control and a sibling host shares our registrable domain, so Lax
  does not close it; what does is that **every POST/PUT/PATCH declares a
  `consumes` no preflight-free request can satisfy** — the sweep holds it and
  owns the banned spellings. Lax and no state-changing GET still stand,
  `csrf { disable() }` is never silent, and **`Strict` is wrong** — the callback
  is a top-level cross-site navigation, so it drops the cookie at login.
- **Injection risk lives exactly in the native aggregation queries.** Column
  names go through a whitelist, never string concatenation.
- **Rate limits are per wedding**, and per link token once links exist —
  **never IP-only** (Korean carrier NAT would block real guests).
- **A secret never travels inside a connection string** (added 2026-08-08,
  `notes/2026-08-08-decision-scaffold-secrets-and-surface.md`) — one sealbox
  key per credential component, because HikariCP's failure path prints the
  whole `jdbcUrl`. `donghaeng/DATABASE_URL` is credential-free JDBC form;
  `DB_USERNAME` and `DB_PASSWORD` are separate keys.
- **No machine-readable introspection surface is internet-reachable** —
  Actuator, `/v3/api-docs`, Swagger UI alike (widened 2026-08-08 from the
  Actuator-only rule). `springdoc.api-docs.enabled` is false by default and is
  enabled only where the document is generated, which is the build. SSH only
  via Tailscale.
- **The servlet container's own error page counts too** (widened 2026-08-10).
  Tomcat's `ErrorReportValve` renders the original exception as HTML with a
  partial stack trace and the Tomcat version. Boot hardens it only
  **conditionally** — `customizeErrorReportValve` sets `showReport`/
  `showServerInfo` false *only if* `server.error.include-stacktrace == NEVER`,
  so `SERVER_ERROR_INCLUDE_STACKTRACE=always` in a deploy platform un-hardens
  the page. That makes the `server.error.*` pins **runtime-load-bearing**, not
  declarative. `TomcatErrorPageHardening` pins both flags unconditionally, and
  the `docker` job asserts no response body ever contains `Apache Tomcat` or
  `Exception Report`. Reached by **a filter that throws during the `ERROR`
  dispatch** — which Spring Security's chain is, since it registers for
  `ERROR` by default, so `#5` puts real code there.
- **Enumeration safety and the link-token rules have no surface in v1** (no
  public page ships) but are not retracted — they bind the release that brings
  the RSVP links back. The public RSVP page must never reveal whether a name
  is on the guest list.

## Domain mechanisms

The product facts these implement are in the root `AGENTS.md`. What follows is
how `api/` is required to implement them.

- **Every wedding-scoped aggregate root carries `wedding_id`**; anything
  reached only through its root does not. So `GuestChange` has it (queried
  independently). This binds tables that don't exist yet — seating and 축의금
  arrive as roots and carry it from the start. It is what makes "every
  wedding-scoped root filters on `wedding_id`" mechanically checkable instead
  of a per-query judgement, and a cross-wedding leak is not an ordinary bug.
  **Amended 2026-08-11** (`notes/2026-08-11-decision-baseline-schema-calls.md`):
  a `wedding_id` present **for integrity is not a root marker**.
  `guest_meal_count` carries one because `meal_type_id` arrives in a request
  body and so bypasses `CurrentWedding` — a row joining one wedding's guest to
  another's meal type inserted cleanly. Composite FKs to `guest (id,
  wedding_id)` and `meal_type (id, wedding_id)` make that row
  *unrepresentable*; the table is still not a root. **The distinction is
  checkable, not arguable: an integrity column appears in a composite FK to a
  parent's `(id, wedding_id)`, a root's does not** — and `#80`'s allowlist test
  carries it as an explicit exception.
  **An integrity `wedding_id` is an FK component and never a query
  predicate**: `select sum(expected_count) from guest_meal_count where
  wedding_id = ?` counts soft-deleted 하객's meals, because `@SQLRestriction`
  cannot reach a native query — it over-counts silently, and over-counting
  보증인원 is money. Every read joins `guest` and filters `guest.deleted_at`.
  Held by `GuestMealCountSchemaTest`.
- **Every delete is soft** (decided 2026-08-10,
  `notes/2026-08-10-decision-soft-delete.md`) — but only on rows a *user* can
  delete. `guest`, `membership`, meal type and `wedding` carry `deleted_at`;
  `guest_change` and the import/ingest records do not, because a deletable
  audit log is not an audit log and deleting an ingest breaks hash
  idempotency. Three consequences:
  **(1)** `@SQLRestriction` filters the JPA path and **does not touch native
  queries** — so the one path the automatic filter cannot reach is the native
  aggregation that computes 보증인원. A missed filter there does not throw, it
  over-counts. Closed by a test (`#17`), not by attention. The default is on
  precisely because forgetting it then shows *fewer* rows rather than leaking
  deleted ones.
  **(2)** The import matcher is the one path that must *see* deleted rows — it
  cannot ask "되살릴까요?" about a row it cannot load. An explicitly named
  bypass, never an ambient one.
  **(3)** Every unique constraint becomes a **partial** index
  (`WHERE deleted_at IS NULL`), or a dead membership blocks a re-invite.
- **Postgres enum types only where the value set is closed forever** — `side`
  qualifies; `group_category`, `source`, `status`, `provider` do not. Use
  varchar plus application-level validation, so adding a value is a deploy and
  not an `ALTER TYPE`. The guest-group list changed twice in one day; assume it
  changes again. (`guest.lifecycle` is not in v1 at all — decided 2026-08-11,
  returns with the RSVP links; the varchar rule binds it whenever it does.)
- **The session never knows the wedding.** Each request resolves
  user → membership → wedding; one person may belong to several.
- **`GuestChange` is the audit log**: one row per changed field with old value,
  new value, who, when, and the source (`MANUAL` / `VENDOR_EMAIL` / `IMPORT`
  plus a nullable FK to the ingest or import that caused it). It is what makes
  "이 숫자 누가 바꿨어?" answerable, and it covers fields the response model
  never reached — meal-type distribution among them.
- **v1 has no `RsvpResponse` / `ResponseMatch`** (dropped 2026-08-06):
  confirmed values are written straight onto `Guest`, and matching runs as
  logic whose results are consumed on screen, never persisted. The response
  model returns when the RSVP links do — the condition is **writes that happen
  while nobody is watching**, which v1 has none of.
- **Import matching loads the wedding's guests once and matches in memory.**
  It is the only v1 operation that is easy to get badly wrong.
- **Import idempotency is by file hash** — a matching hash is not processed at
  all; rows identical in every field are skipped silently; only 인원수 can
  actually conflict (`notes/2026-08-07-decision-import-idempotency.md`).
