# Donghaeng (동행)

Wedding-journey companion web service for couples — centered on a guest
ledger (하객·좌석·축의금). See README.md for the product pitch.

## Pick up here (last session: 2026-08-11)

**Substrate is one item from closed, and the order after it is decided.**
Design, both implementors' methodologies, the tempo, the work tracker and the
build workflow were all settled 2026-08-07/08; each has its own section below
and its own record in `notes/`. Read those sections for the rules — this one
only says where the work is.

**What has landed** (all merged; `main` clean): `api/` and `web/` scaffolding
(`#1`, `#2`), the profile split (`#50`), CI with its `prod-boot` and `docker`
jobs plus the local merge gate (`#36`, `#57`, `#58`), schema ownership moved
to hand-applied DDL (`#59`), a real-config boot test (`#41`), the RFC 9457
error contract and Tomcat error-page hardening (`#4`), `web/`'s linter and
the design-value checker (`#45`), a liveness probe for a guard that could
disable itself (`#60`), and — 2026-08-11, `#83` — the **v1 baseline schema**
(`#3`), which was the last horizontal stop.

So there is application code and now a schema, but still **no domain code**:
`api/src/main` is four config guards, the error contract, and one migration
file; the only things that read the schema are three JDBC tests. `web/src` is
App, the query client and the test harness. **The first entity is written in
`#37`.**

**The schema is merged but has not been applied to any real database.** Flyway
runs in tests only (2026-08-09), so `#83` landing changed nothing in dev or
prod — the founder types `V1__baseline_schema.sql` by hand, wrapped in an
explicit `BEGIN; … COMMIT;` (Flyway supplies that transaction in tests, which
is why the file does not contain one). Read the column sizes while typing:
`ddl-auto: validate` compares JDBC type codes only, so a mistyped
`varchar(20)` boots fine and fails later on a long 하객 이름. Until this is
done, dev has no tables and `#37` cannot run against it.

**The order from here** (decided 2026-08-10,
`notes/2026-08-10-decision-auth-gate-and-sequence.md`; `#80` inserted
2026-08-11):

    #3       baseline schema        ✔ merged 2026-08-11 (#83)
    #80      wedding_id allowlist meta test   ← next, and small
    #6/#37   social login — session issuance + CurrentUser
    #7       웨딩 만들기 — the first membership exists here
    #5       CurrentWedding resolution + cross-tenant 404
    #8~      vertical, all of it

`#80` was not in the 08-10 order. It is inserted here because `#3` created
the exception it has to carry (`guest_meal_count`'s integrity-only
`wedding_id`), and because `#37` is where the **next new table** appears —
standing the checker up first means it is watching when that table is
written rather than after. It needs no entities, only `information_schema`.

Auth lands **after** login rather than before it, because no v1 requirement
can be built ahead of auth anyway — every one of `#7`–`#24` sits behind "who
is asking" and "which wedding", so the only alternative to a real session is
a hardcoded `userId` threaded through fifteen endpoints. **The resolver is
the gate, not Spring Security's filter chain** — see **Security posture**.
That call split `#5` along the user/wedding axis (the session half moved into
`#6`) and shrank `#62` to a single OAuth-callback case owned by `#6`.

The five working agents are in place (see **Agents** below), delegation is
automatic, and the local DB, sealbox entry and ports are provisioned.

All three calls from the flow design were **confirmed on 2026-08-07**:

1. **Search is in v1**, on the ledger. Filters (side + attendance) narrow a
   list; they do not find a person. The real trigger is "김영수 못 온대" and
   that needs two syllables, not a 400-row scroll. It is the second most-used
   control after the attendance chip. Implemented as a **Field variant**, with
   the filter chips as a **Tag with a selected state** — the ten-component
   inventory still holds.
2. **보증인원 is not asked at onboarding** — couples sign up before booking a
   venue. The ledger works fully without it; the comparison simply does not
   render until it is set in 설정 · 웨딩 정보.
3. **Meal types are not asked at onboarding** — this also closes the question
   parked on 2026-08-06. A couple first meets meal types when a guest needs
   유아식, and adding one belongs in the detail sheet at that moment.

Onboarding is therefore **date and names only.**

Only one small item is still open: **`GuestChange` retention** — narrowed
2026-08-11, since **deletion cannot be its trigger** (the couple deletes both
for 오타 and for 못 온다, and we cannot tell which). The other three are closed:
the import file hash (2026-08-10, by the soft-delete record), the 관계 synonym
table and 유아식 against 보증인원 (both 2026-08-11, the synonym table **deleted
rather than written**). Open items live as `open-question` issues
(`gh issue list --label open-question`) rather than as a bullet list here —
see **Work tracking** below.

Working style for this project: **talk design through, don't hand over option
menus.** The founder is the domain owner, and the biggest corrections have all
come from domain facts that could not be derived from these notes — 보증인원 is
the venue's number, attendance arrives via parents and KakaoTalk, the real
import risk is re-uploading the same file, the parents' sheet lists attendees
and so never states attendance. State a read, ask one open question, converge.
Reserve multiple-choice for operational forks.

## Status

Building the substrate (as of 2026-08-10). Vision, core scope, product
values, the MVP's hard design spots, the tech stack, the domain model, the
headcount aggregation, the **v1 scope cut**, and the **design system
foundations** are decided — see the
decision records in `notes/` (`2026-07-26-decision-core-scope.md`,
`2026-07-26-mvp-v1-requirements.md`,
`2026-07-30-design-guest-ledger-hard-spots.md`,
`2026-07-30-decision-tech-stack.md`, `2026-08-03-design-domain-model.md`,
`2026-08-05-design-meal-headcount.md`,
`2026-08-06-decision-v1-scope-and-meals.md`,
`2026-08-06-design-ledger-and-import.md`,
`2026-08-06-decision-drop-response-model.md`,
`2026-08-06-review-scale-and-extensibility.md`,
`2026-08-07-design-system.md`,
`2026-08-07-decision-import-idempotency.md`,
`2026-08-07-design-screens-and-flow.md`,
`2026-08-07-decision-backend-tdd-methodology.md`,
`2026-08-07-decision-backend-architecture.md`,
`2026-08-07-decision-backend-api-conventions.md`,
`2026-08-08-decision-frontend-testing-methodology.md`,
`2026-08-08-decision-frontend-architecture.md`,
`2026-08-08-decision-development-tempo.md`,
`2026-08-08-decision-work-tracking.md`,
`2026-08-08-decision-build-workflow.md`,
`2026-08-08-decision-merge-gate.md`,
`2026-08-08-decision-scaffold-secrets-and-surface.md`,
`2026-08-09-decision-schema-ownership.md`,
`2026-08-10-decision-cross-tenant-status-code.md`,
`2026-08-10-decision-design-value-enforcement.md`,
`2026-08-10-decision-auth-gate-and-sequence.md`,
`2026-08-10-decision-soft-delete.md`,
`2026-08-11-decision-baseline-schema-calls.md`,
`2026-08-11-decision-import-row-rejection.md`,
`2026-08-11-decision-deletion-and-infant-meals.md`). Read them newest-first: the
2026-08-06 records supersede parts of nearly every earlier note — including
each other — and every affected note carries a banner saying what changed.
**Design has no remaining blocker.** Application code exists but no domain
code yet — see **Pick up here** for exactly what has landed and what is next.
Success criteria are deliberately deferred until after the MVP is built.

## Stack (decided 2026-07-30)

Separated frontend and backend, per `notes/2026-07-30-decision-tech-stack.md`:

- `api/` — Kotlin + Spring Boot (JDK 21, Gradle KTS), JSON API only.
  Spring Data JPA + Flyway (**tests only** — see below), **PostgreSQL 16**
  (ratified 2026-08-08; the native aggregation queries are where a version
  difference yields a different *number* rather than an error). JUnit 5 +
  Testcontainers.
- `web/` — React + TypeScript + Vite, built to static files. **v1 ships one
  bundle** (the couple app); the separate guest RSVP bundle arrives with the
  RSVP links. The rule holds whenever they land: a guest must never download
  the couple app's code.
- **Web and mobile are two layouts, one codebase** (reaffirmed 2026-08-07).
  Shared: one route, one data layer, one token set. Split: the layout and
  `GuestRow`. Hold that line — the moment the same number is computed twice,
  the two versions can disagree about it. **PC earns its existence through the
  aggregation rail** (group and meal breakdowns) and the contact column, not
  through being wider; without them it is a wide phone screen.
- Auth: **네이버 · 카카오 · 구글** OAuth for the couple (widened 2026-08-06),
  server-side session behind an HttpOnly cookie. Guests are never
  authenticated.
- Deployment follows the workspace standard (`../../notes/infra-zones.md`):
  static → Cloudflare Pages, API → VPS docker compose, managed Postgres.

Two standing client rules (`notes/2026-07-30-decision-client-strategy.md`),
kept so a native couple app stays possible without paying for it now:
session lookup reads a token from the request rather than a cookie, and
**all computation stays server-side** — the API returns conclusions, not
rows to compute over. The guest RSVP page is web forever.

## Schema ownership (decided 2026-08-09)

Full record: `notes/2026-08-09-decision-schema-ownership.md`. This **supersedes**
the earlier "Flyway owns the schema everywhere".

- **Flyway runs in tests only. Every DDL statement against a real database is
  applied by the founder, by hand.** A migration running unattended at startup
  is irreversible work at the moment an environment is least observed;
  `clean-disabled` guards one catastrophic verb and nothing else.
- **The migration SQL files stay authoritative and stay the only copy.** They
  are what the tests build from and what the founder types. A second copy of
  the DDL is what would make this unworkable.
- **`ddl-auto: validate` in dev/prod is the drift detector**, but its reach is
  narrower than the name suggests: Hibernate 6 compares mapped columns' JDBC
  **type codes only** — not length, precision, scale or nullability, and
  nothing about indexes, constraints or defaults. **A hand-typed `varchar(20)`
  under a `length = 255` mapping passes**, then fails at INSERT on a long name.
  Size is the likeliest drift when a person types the DDL, and it is uncaught.
- **The environment outranks every yml.** `SPRING_FLYWAY_ENABLED=true` in the
  deploy platform reverses this decision with the whole suite green, so the
  resolved values are asserted at **startup**, not only in tests. Any guard
  that reads a committed file cannot see the environment that actually runs.
- **Agents may not reach a non-local database** — in Claude Code sessions.
  `.claude/hooks/db-guard.sh` refuses any DB client command whose target is not
  loopback or is unresolvable (a `PGSERVICE`, a config file, a shell variable).
  It blocks the founder's own agent sessions on purpose. **It does not cover**
  SSH tunnels, clients it does not know by name, script files, non-Bash tools,
  or Codex — the full list is in the record, and the hook stops the casual path
  rather than a determined one.
- **`prod-boot` rehearses the configuration, never the schema.** It is a Gradle
  `Test` task, so it inherits the Flyway opt-in *even in CI* and builds its own
  schema from the migrations. Only **`docker`** runs the real deploy shape (the
  packaged jar, Flyway off), so #55 — applying the migration SQL to the
  throwaway Postgres once entities exist — lands on `docker` alone. Do not read
  a green `prod-boot` as a rehearsed deploy.

## Development tempo (decided 2026-08-08)

How every unit of implementation work is cut and accepted, on both sides of
the seam. Full record: `notes/2026-08-08-decision-development-tempo.md`. The
goal it serves is the founder's: **minimize the cognitive debt of AI-written
code** — keep an actual working model of what was built.

- **Cut vertically, by requirement — never horizontally by layer.** One
  requirement = one Red/Blue/Green cycle = one review = one explanation =
  one commit = **one stop**. "웨딩 생성" and "웨딩 정보 수정" are separate
  stops. A layer slice (entity, then API, then service) has no Red Gate test
  worth writing and hides the domain question until the layers meet — and
  the seams between separately-approved layers are exactly where an AI
  silently disagrees with itself.
- **One honest exception — the substrate.** Scaffolding, the Flyway baseline
  schema, ProblemDetail/error-handling wiring, and
  session→membership→wedding resolution are not requirements and are done
  horizontally, once, at the front. Everything after is vertical.
- **Slice size is measured in new concepts, not lines: one or two per
  stop.** The founder reads via the explanation document rather than the raw
  diff, so the binding constraint is how many unfamiliar ideas land at once,
  not how many files changed.
- **Order at every stop:** implementor → `reviewer` + `security-manager` →
  fix → `explainer` → founder reads and takes the quiz → commit. The
  explanation is written only after findings are resolved, so it describes
  verified code.
- **The comprehension gate is tiered**, so it survives past week two. A stop
  introducing a new concept gets the full explanation + quiz; a stop
  repeating an established pattern gets `reviewer`'s report only. The
  implementor states which tier it thinks its stop is; the founder
  overrides freely.
- **Never let the author explain its own work.** An implementor explaining
  its own change explains its intent, so a bug gets described as the
  bug-free version it meant to write — turning unknown debt into false
  confidence, which is worse than no gate. This is why `explainer` exists
  as a separate agent and why it carries none of the implementation
  session's context.

## Work tracking (decided 2026-08-08)

**GitHub Issues on `BeomSeogKim/Donghaeng`, reached with `gh` — no Jira, no
board, no backlog file.** Full record:
`notes/2026-08-08-decision-work-tracking.md`.

- **`notes/` is why; an Issue is what's left and where it stands.** An
  issue never decides anything. Rationale in an issue body that isn't in
  `notes/` is a bug — move it to a record and link. This keeps the single
  source of truth intact while the tracker stays perishable.
- **One issue = one requirement, not one stop.** A stop is cut at build
  time by new-concept count, so one issue may take two or three stops and
  as many commits. It closes when the requirement is done.
- **`Closes #N` (or `Refs #N`) in the commit body.** This is the whole
  mechanism: state updates as a by-product of a commit message that gets
  written anyway, so nothing depends on remembering to tidy a board.
  **One keyword closes exactly one issue** — found the hard way on `#83`.
  `Closes #33, #35` closes `#33` and silently leaves `#35` open; the keyword
  has to be repeated (`Closes #33, closes #35`) or split across lines. Nothing
  goes red, and the tracker is quietly wrong, which is the one failure this
  mechanism was chosen to avoid. **Verify closure after a merge** whenever a
  commit or PR names more than one issue.
- **Two milestones** — `v1` and `post-v1` (deferred, never cancelled).
  **Four labels** — `api`, `web`, `infra`, `open-question`. Don't add more;
  labels stop meaning anything the moment they multiply.
- **`open-question` closes only by writing a `notes/` record.** These are
  the small undecided items that otherwise evaporate.
- **Leftover concepts become issues.** When an implementor stops after the
  first concept and reports what it left, file those as issues before
  moving on — that report is the intake path for most new work.

## Build workflow (decided 2026-08-08)

Full record: `notes/2026-08-08-decision-build-workflow.md`.

- **One stop = one branch = one PR into `main`.** The diff `reviewer` and
  `explainer` are handed is `main...<branch>` — that is what makes "the
  diff of a stop" mechanical instead of a judgment call. Fix commits from
  review stay on the branch. `Closes #N` goes in the PR.
- **A red check is never merged.** Not "unrelated", not "fix it after". CI
  runs `api/` build + test, `web/` typecheck + test, and ktlint on every
  push and PR — plus two jobs that exist because **green has to mean
  deployable, not compiling** (built 2026-08-08): `prod-boot` boots the
  committed `application-{dev,prod}.yml` for real, and `docker` builds the
  image, runs it under the prod profile, and asserts it serves HTTP, reaches
  Postgres, and answers 404 on `/v3/api-docs`, `/swagger-ui`, `/actuator` —
  a negative assertion on the shipped artifact, the only kind that survives
  a profile-precedence mistake.
- **A boot test must be named `*ProfileBootTest`.** The `prod-boot` job
  selects by that pattern, so a boot test named anything else silently
  vanishes from that check — and does *not* go red, because the `api` job
  still runs it. The name is a contract, not a style.
- **The merge gate is local, and leaky on purpose** (decided 2026-08-08,
  `notes/2026-08-08-decision-merge-gate.md`). Branch protection is
  unavailable — private repo on the free plan returns 403 — so
  `.githooks/pre-push` refuses a direct push to `main` and a `PreToolUse`
  hook blocks `gh pr merge` unless `gh pr checks` is green, failing closed.
  **A merge from the GitHub web UI bypasses both.** Revisit the moment the
  repo goes public/paid or a second person gets commit access; delete the
  local gate then rather than stating the rule twice.
- **The seam is type-checked, not just documented.** springdoc generates
  OpenAPI from the controllers; `web/` generates its TS types from that. A
  renamed field then breaks the frontend build instead of leaving MSW mocks
  green against a shape the API no longer returns. `docs/api-spec.md`
  stays and stays authoritative for **meaning** — what an endpoint is for,
  which invariant it protects — which OpenAPI cannot carry.
- **A requirement spanning the seam is one parent issue with two
  sub-issues**, backend and frontend. Each child is its own stop, review,
  explanation, and PR; the backend child goes first because the spec is the
  seam. The parent closes when both children do. **Children are created
  when the parent is picked up, not up front** — `#6` → `#37`/`#38` is the
  worked example; the other cross-seam issues are still whole.
- **`explainer`'s document is linked from its issue** as a comment, so the
  issue is the index of explanations — the documents themselves are
  deliberately not committed.
- **An implementor may push back on a review finding once**, in writing,
  stating why it is wrong. If the reviewer holds, the founder settles it.
  Silent capitulation and silent dismissal look identical in a report,
  which is why this is a rule and not a vibe.
- **Local infra**: DB `donghaeng`, role `donghaeng_app`, connection string
  in sealbox at `donghaeng/DATABASE_URL` (never a `.env`). No `_test`
  database — backend tests use Testcontainers. Ports 8080 (`api/`), 3000
  (`web/`), registered in `../../notes/local-infra.md`.

## Backend development methodology (decided 2026-08-07)

`backend-implementor` builds every unit of `api/` work through three gates,
per `notes/2026-08-07-decision-backend-tdd-methodology.md`:

1. **Red Gate** — a failing test for the requirement, written first.
2. **Blue Gate** — the minimum implementation that turns it green, nothing
   the test doesn't ask for.
3. **Green Gate** — refactor for stability and extensibility with the suite
   green throughout.

Per requirement, not per PR. This governs *when* a test is written; the
existing rule on *what kind* (JUnit 5 + Testcontainers, mandatory for
wedding-scoped queries, aggregation, import, tokens) is unchanged.

## Backend architecture (decided 2026-08-07)

Full record: `notes/2026-08-07-decision-backend-architecture.md`.

- **Packages are domain-based** (`wedding/`, `guest/`, `guestimport/`, `auth/`),
  each self-contained with its own Controller/Service/Repository/Entity —
  not layer-based (`controllers/`, `services/`, `repositories/`). It is
  `guestimport/` and not `import/` because **`import` is not a legal Kotlin
  package name here** — it compiles, but ktlint's `standard:package-name`
  rejects it. Named 2026-08-11 for the table; `intake` lost because it would
  also plausibly hold `email_ingest`, which is a separate domain.
- **Layers stay shallow**: Controller (DTO) → Service (tx boundary,
  invariants, aggregate recompute, `GuestChange` writes) → Repository (JPA
  + native aggregation queries). No hexagonal/ports-and-adapters layer.
- **Split a class when it starts doing two distinct things, not before.**
  Kotlin `internal` is the default visibility inside a domain package;
  only the Controller and an explicit cross-domain contract are `public`.
  Cross-domain access always goes through that contract, never straight
  into another domain's entities or repository.
- **Tests mirror the domain tree**, not the layer tree
  (`src/test/kotlin/.../guest/` next to `src/main/kotlin/.../guest/`).
  Three kinds, chosen by where a requirement's risk lives: Service unit
  tests (JUnit 5, no DB), Repository Testcontainers tests (mandatory for
  wedding-scoped/aggregation/import/token paths, unchanged from the TDD
  note), Controller contract tests (against `docs/api-spec.md`).

## Backend API conventions (decided 2026-08-07)

Full record: `notes/2026-08-07-decision-backend-api-conventions.md`.

- **Success responses have no envelope** — the resource's own DTO, returned
  directly. No `{data: ...}` wrapper: one first-party client, no
  pagination, no API versioning, so it buys nothing.
- **Errors are RFC 9457 Problem Details** (`type`/`title`/`status`/
  `detail`/`instance`), via Spring Boot 3's native `ProblemDetail` —
  `spring.mvc.problemdetails.enabled=true` plus one global
  `@ControllerAdvice`. Extended with a **`code`** field (e.g.
  `GUEST_NOT_FOUND`) so the frontend switches on a stable string instead
  of parsing `detail`.
- **HTTP status follows standard verb/outcome mapping**: 200 read/update,
  201 create, 204 delete, 400 validation, 404 not found, 409 conflict, 401
  unauthenticated, 500 unhandled (masked per security posture).
- **A cross-tenant request is 404, never 403** (decided 2026-08-10,
  `notes/2026-08-10-decision-cross-tenant-status-code.md`, superseding one
  row of the 08-07 table). Any resource addressed by a caller-supplied id
  whose owning wedding the caller is not a member of returns the same
  response as an id that does not exist — otherwise the pair is a wedding-id
  oracle. **403 means the caller *is* a member and lacks a privilege within
  that wedding, so v1 has no correct use for it.** 404 is the default and
  not a per-endpoint exception, for the same reason `wedding_id` sits on
  every root: a default cannot be forgotten, a judgement call can.
- **DTO naming**: `XxxRequest` / `XxxResponse`, mapped via extension
  functions in the domain package. Entities never serialize directly.
- **No `/v1` prefix** — no second API version planned; free to add later.
- **ktlint** applied via the standard Gradle plugin, default ruleset.

## Frontend development methodology (decided 2026-08-08)

`frontend-implementor` runs the same three-gate discipline as the backend,
per `notes/2026-08-08-decision-frontend-testing-methodology.md`, scoped to
where frontend risk actually concentrates rather than applied to every
component:

1. **Red Gate** — an integration test written before the component/hook
   exists, confirmed failing for the right reason.
2. **Blue Gate** — the minimum implementation that turns it green.
3. **Green Gate** — refactor with the suite green throughout.

Mandatory for: the ledger/headcount/meal-count display, every mutation
flow (attendance tap, guest edit, CSV import, vendor-email conflict
resolution), and anything branching on the API's error `code` field. Not
mandatory for static layout, one-off screens, or logic-free display
components — chasing coverage there is cost with no return.

Default to **integration tests** (Vitest + React Testing Library,
rendering the real component) over isolated unit tests; mock only the
network boundary, with **MSW** — never the app's own data-layer module or
hooks. Query by role/label/text first, `data-testid` only when nothing
semantic works. **Playwright** stays thin: 2-5 true cross-page critical
flows, never a coverage target.

## Frontend architecture (decided 2026-08-08)

Full record: `notes/2026-08-08-decision-frontend-architecture.md`.

- **Folder structure starts flat** (`src/{components, pages, lib, hooks}`)
  and escalates to `src/features/<name>/` only once a feature's files are
  actually scattering — not before. Feature-Sliced Design's full six-layer
  taxonomy is explicitly rejected for this team size. **No barrel files**,
  ever; never reach into another feature's internals.
- **Server state — anything that's a client-side copy of API/DB data —
  goes through React Query (TanStack Query)**, never a hand-rolled
  `useEffect` + `fetch` + `useState`. A mutation's `onSuccess` writes the
  response straight into the query cache — this is the mechanism behind
  "every mutation response carries the recomputed aggregate."
- **Client state escalates one rung at a time**: `useState` → lift to the
  common parent → `useReducer` → Context (wrapped in a custom hook,
  immediately) → a client-state library, only once Context is provably
  struggling. Never start at Context or a library.
- **Hooks, not containers.** No `XContainer`/`XView` split; extract
  data-fetching/derivation/subscription logic into a custom hook. This is
  the mechanism "web and mobile are two layouts, one codebase" runs on.
- **The token bridge** (built 2026-08-08 with the scaffold): `web/src/index.css`
  **imports** `design/tokens.css` rather than copying it, and every `@theme`
  entry is a `var()` reference, never a literal — so utilities resolve to
  tokens at runtime and the dark theme's `:root[data-theme="dark"]` overrides
  flow through for free. Each Tailwind namespace is cleared to `initial`
  first, so `bg-slate-100` and `rounded-lg` **do not exist**. Note the
  limit, which is real: the *named scale* is dead, but arbitrary-value
  syntax (`bg-[#ff0000]`, `text-[13px]`) still compiles — that gap is not the
  bridge's to close, and it is now closed by `check:design` (below).
- **The fourth design value is enforced by our own checker, not by the
  linter** (built 2026-08-10, `notes/2026-08-10-decision-design-value-enforcement.md`).
  The bridge mechanically kills hardcoded colour/size/radius by clearing each
  Tailwind namespace, but it cannot see `duration-300` (v4 has no duration
  namespace) or any arbitrary value. `web/scripts/design-values.mjs` covers
  both, run as `npm run check:design` inside `npm run lint`, in CI.
  **It is hand-written because the rule cannot be expressed in the linter** —
  Biome's only extension point is GritQL over Rust's `regex`, which has no
  lookaround, and the rule needs "flag `[...]` *unless* its content is
  `var(--dh-*)`" so that `border-[var(--dh-gold)]` passes. A checker next to
  a linter looks like duplication and is not; do not try to fold it in.
  Biome itself was likewise not a preference — `typescript@^7` is the native
  port with no compiler API, so typescript-eslint cannot parse this repo at
  all. Known cost: Biome has **no type-aware rules**, so a floating promise
  in a mutation handler is caught by nothing (#71).
- **Only design-carrying prefixes are checked; layout arbitrary values pass**
  (founder's call, 2026-08-10). `grid-cols-[1fr_auto]`, `z-[60]`,
  `content-['']` are legal — they are not colour, size, radius or duration.
  A prefix earns its place on the list **only if `design/tokens.css` has a
  token family behind it**, which is what makes membership checkable instead
  of arguable. Viewport units are a value-shape exception (`min-h-[100dvh]`
  passes) because they are a relationship to the device, not a step on a
  scale — but the match is anchored, so `min-h-[calc(100dvh-44px)]` still
  fails on the hardcoded tap floor.
- **`useEffect` is for external sync only.** Before writing one, name the
  outside-of-React thing it synchronizes with. A user action (a tap, a
  submit) belongs in that action's event handler, never an Effect watching
  a trigger flag.

## Design system (decided 2026-08-07)

Tokens are in [`design/tokens.css`](design/tokens.css), components in
`design/components/` (sources in `parts/`, built previews in `dist/` via
`python3 design/components/build.py`), and the reasoning is in
`notes/2026-08-07-design-system.md`. The rendered library is mirrored to the
claude.ai/design project *Donghaeng Design System* — **that is a view, not a
store**: anything decided there comes back to this repo or it does not
survive. The thesis: **동행 is an instrument, not
a celebration** — the thing to beat is a spreadsheet with a SUM in the next
column, so we win by being as calm as one and requiring less work, never by
being prettier. That rules out the wedding-stationery register entirely.

But **restraint is not cheapness** (learned the hard way 2026-08-07: the first
ground was `#f4f5f7`, the SaaS default, and it did not carry the high-end
positioning at all). Premium here is carried by **material, not saturation** —
which is the same reason 백자 is prized for being *almost* white.

- **Light is 백자 · 금박, dark is 나전칠기.** 유백색 ground, 먹 ink, **자적
  `#73304e`** primary (the 비빈 rank colour), gold as metal. Dark inverts to
  옻칠 ground with gold as primary. One system's day and night, not two designs.
  v1 ships light only.
- **Gold is 3.3:1 on porcelain and 7.8:1 on lacquer.** So in light it may
  **never carry text** — hairlines, meter, brand mark only — and in dark it is
  the primary text accent. The same token, opposite rules per theme; a token
  name alone will not warn you.
- **Tokens are named for their role, never their colour.** The first version
  used `--dh-cheong`/`--dh-hwang`/`--dh-hong` and one palette change made every
  name a lie. Now `--dh-primary` / `--dh-attention` / `--dh-danger`.
- **Nothing hardcodes a colour, size, radius, or duration.** Everything reads
  a token. Tailwind consumes them via `@theme` so utilities and tokens cannot
  disagree.
- **Every digit that can change in place is tabular** — headcount, meal
  counts, 축의금 later. This is 정직함·믿음직함 in typography: a number whose
  width shifts as it counts reads as unstable. In `web/` the mechanism is
  Tailwind's **`tabular-nums`** utility, not `.dh-num` (decided 2026-08-08 —
  two mechanisms existed and the same mandatory rule must not be expressed
  two ways).
- **Gold is not a Tailwind colour utility** (decided 2026-08-08). It is
  deliberately absent from the `@theme` bridge's `--color-*` namespace, so
  `text-gold` does not exist; the hairline, meter, and brand mark reach for
  `var(--dh-gold)` directly. Gold is 3.3:1 on porcelain and 7.8:1 on
  lacquer, so the identical utility would be correct in dark and unreadable
  in light — the one case a token name provably cannot warn you about.
- **불참 is neutral, never red; 참석 is 초록** (초록원삼, the robe of a 반가
  bride). A guest who cannot come is a fact, not an error. Red belongs to
  destroying data only — and destructive actions always carry a verb and are
  outlined not filled, because 자적 and 대홍 are both reds.
- **Ledger rows are flush, hairline-separated — never cards.** Per-row cards
  cost ~8px of vertical rhythm each and break scanning at 400 rows. Radius is
  for things genuinely detached: chips, buttons, sheets.
- **Body text never goes below 15px.** Hangul packs more strokes into the em
  than Latin. 13px is for metadata fragments, never sentences. Korean running
  text gets 1.65 leading, not the Latin-typical 1.5. No italics — Korean has
  no italic tradition and synthesised obliques look broken.
- **Two faces: Pretendard for UI, RIDIBatang for display.** RIDIBatang is used
  in exactly three places — the headcount, screen titles, the brand mark — and
  **never the list**: Korean serif at 15px across 400 rows is slower to scan.
  Both are in `design/fonts/` with licences and measurements; regenerate the
  preview subsets with `design/fonts/subset.sh` when preview text changes
  (`build.py` warns when a character falls outside the subset).
- **Typeface candidates are decided by measuring the font, not by taste.**
  Arita Buri and Noto Serif KR were rejected because they have no `tnum` and
  default to proportional figures, so they cannot carry the headcount; Song
  Myung and Hahmlet because they omit most of the 11,172 Hangul syllables and
  this product's content is people's names. Read `GSUB`/`hmtx` before
  recommending a face.
- **Dark theme is defined, not shipped.** v1 is light only; the tokens carry
  both so it is never a retrofit.
- Ten components plus four foundation cards cover v1 — inventory in the note.

## Security posture (decided 2026-07-30)

Full record in `notes/2026-07-30-decision-network-security.md`. The parts
that constrain everyday work:

- All tokens (v1: session and invite; later: shared link, per-guest link):
  ≥128-bit CSPRNG, **stored SHA-256-hashed**, constant-time compared, masked
  in logs. Privileges and lifetimes differ per kind — the per-guest link can
  only respond as that guest, never read.
- **Only a provider-*verified* email is an account merge key** (decided
  2026-08-11, `notes/2026-08-11-decision-baseline-schema-calls.md` §A,
  narrowing 2026-08-06 §3). Kakao returns `is_email_verified` as a field
  separate from the address and can hand back an unverified one; Naver's is
  user-editable. So merging on a raw email is a **full ledger takeover with no
  token, no expiry and no invite** — the invite token was tightened to
  single-use/72h for being the most dangerous thing here, and this granted the
  same access for free. An unverified address is not stored: `app_user.email`
  stays NULL, `email_verified_by` records whose word we took, and a CHECK binds
  the two. **v1 has no account-linking flow**, so the second account simply
  stands alone. Three constraints hold it, and each closes a way of writing a
  merge key that is not one: **`email_verified_by in ('GOOGLE', 'KAKAO')`** —
  Naver asserts nothing, so `'NAVER'` can never be true, and this is the one
  value set in the project where "a new value is a deploy, not an `ALTER
  TYPE`" cuts *for* the constraint, because each name is a claim that a company
  checked mailbox control; **a shape CHECK** (`like '%_@_%'`, no whitespace),
  because `''` is a legal varchar and a stored `''` is one `app_user` shared by
  every stranger whose provider returned an empty email; and the unique index
  on **`lower(email collate "C")`**, because the database's own `lower()` is
  not injective (`lower('KİM@X.COM') = lower('KIM@X.COM')`) and an index over a
  collatable expression can be silently invalidated by a glibc/ICU upgrade.
  `#82` is the matching obligation on `#37` — an index that forbids the
  duplicate does not make the lookup find it, the lookup must use the *same*
  expression, and the app-side normalisation must be an **ASCII-only**
  lowercase, since Kotlin's `String.lowercase()` is not `lower(... collate
  "C")`.
- **The auth gate is our resolver, not Spring Security's filter chain**
  (decided 2026-08-10, `notes/2026-08-10-decision-auth-gate-and-sequence.md`).
  `authorizeHttpRequests` stays `permitAll` in **every** environment; what
  rejects a request is `user → membership → wedding` resolution failing. The
  asymmetry is retrofit cost: flipping the filter chain later is one line and
  turns every fixture-less test red at once, whereas retrofitting the resolver
  means threading a parameter through every endpoint written in the meantime,
  by hand and silently. So the resolver is present from the first endpoint and
  the filter chain is defense in depth. This is a design, not deferred
  hardening, and it is only honest because two tests hold it: an anonymous
  request to a wedding-scoped endpoint is 401, an authenticated non-member is
  404. **Forgetting the resolver must fail closed** — `#5` ships either an
  interceptor that denies undeclared handlers or a build-time check that every
  handler takes a resolved principal. Never neither.
- **CSRF in v1 is `SameSite=Lax` plus no state-changing GET** — the pair, not
  either half. Lax withholds the cookie on cross-site POST and admits it only
  on top-level GET navigation, so a state-changing GET reopens exactly what
  Lax closed. Spring Security's CSRF filter being off is therefore an explicit
  act with a stated substitute, never a silent `csrf { disable() }`; the token
  itself is defense in depth and belongs to `#48`. **`SameSite=Strict` is
  wrong here** — the OAuth callback is a top-level cross-site navigation, so
  Strict drops the cookie at the moment of login.
- Injection risk lives exactly in the native aggregation queries; column
  names go through a whitelist, never string concatenation.
- Parsed vendor email is rendered as text, never as HTML.
- Rate limits are per wedding, and per link token once links exist —
  **never IP-only** (Korean carrier NAT would block real guests).
- **A secret never travels inside a connection string** (added 2026-08-08,
  `notes/2026-08-08-decision-scaffold-secrets-and-surface.md`) — one sealbox
  key per credential component, because HikariCP's failure path prints the
  whole `jdbcUrl`. `donghaeng/DATABASE_URL` is credential-free JDBC form;
  `DB_USERNAME` and `DB_PASSWORD` are separate keys. A recorded exception to
  the workspace pattern in `../../notes/local-infra.md`.
- **No machine-readable introspection surface is internet-reachable** —
  Actuator, `/v3/api-docs`, Swagger UI alike (widened 2026-08-08 from the
  Actuator-only rule). `springdoc.api-docs.enabled` is false by default and
  is enabled only where the document is generated, which is the build.
  SSH only via Tailscale.
- **The servlet container's own error page counts too** (widened 2026-08-10,
  found auditing #4). Tomcat's `ErrorReportValve` renders the original
  exception as HTML with a partial stack trace and the Tomcat version.
  Boot does harden it — but **conditionally**:
  `TomcatWebServerFactoryCustomizer.customizeErrorReportValve` sets
  `showReport`/`showServerInfo` false *only if*
  `server.error.include-stacktrace == NEVER`. So
  `SERVER_ERROR_INCLUDE_STACKTRACE=always` in a deploy platform un-hardens
  the page, which makes the `server.error.*` pins **runtime-load-bearing**,
  not merely declarative — the same environment-outranks-yml shape as the
  schema-ownership guard. `TomcatErrorPageHardening` pins both flags
  unconditionally, and the `docker` job asserts no response body ever
  contains `Apache Tomcat` or `Exception Report`.
  Reached by **a filter that throws during the `ERROR` dispatch** — which
  Spring Security's chain is, since it registers for `ERROR` by default, so
  #5 puts real code there. (Not by our own `/error` handler throwing: that
  is a `@RequestMapping` method, so the exception resolvers catch it and
  `GlobalErrorHandler` answers, masked.)
  The earlier rule named only *machine*-readable surfaces; this is the
  human-readable one, and it was unnamed for that reason alone.
- Enumeration safety and the link-token rules have **no surface in v1** (no
  public page ships) but are not retracted — they bind the release that
  brings the RSVP links back.

## v1 scope (cut 2026-08-06)

> **A tool the couple operates for headcount and meal planning, plus a
> vendor-email parser that saves them typing.**

Intake in v1 is exactly two paths — the couple entering it directly, and a
parsed vendor RSVP email — plus CSV import, which builds the ledger rather
than answering it. **Our own RSVP links (shared and per-guest) are deferred,
not cancelled.** No guest meets the product in v1. Full record in
`notes/2026-08-06-decision-v1-scope-and-meals.md`.

Consequence worth remembering: the review *queue* has nothing to fill it in
v1 — but **matching still runs**, in two places: vendor-email paste and CSV
import. Both resolve on a screen the couple is already looking at rather
than queuing. Direct entry targets a specific guest, so it needs no matching
at all.

**The ledger and the headcount are one screen** (decided 2026-08-06). Tapping
attendance moves the number in place. Splitting them turns one action into
tap → navigate → check → return, which is exactly what a spreadsheet with a
SUM already does. This is the first fixed point of the screen design.

## Standing design constraints (2026-07-30 → 08-06 decisions)

- Every intake channel converges on **one matching pipeline**. v1 has no
  `RsvpResponse` / `ResponseMatch` (dropped 2026-08-06): confirmed values are
  written straight onto `Guest`, and matching runs as logic whose results are
  consumed on screen, never persisted. The response model returns when the
  RSVP links do — the condition is **writes that happen while nobody is
  watching**, which v1 has none of.
- **`GuestChange` is the audit log**: one row per changed field with old
  value, new value, who, when, and the source (`MANUAL` / `VENDOR_EMAIL` /
  `IMPORT` plus a nullable FK to the ingest or import that caused it). It is
  what makes "이 숫자 누가 바꿨어?" answerable, and it covers fields the
  response model never reached — meal-type distribution among them.
- Ambiguity is never guessed — 2+ candidates means `needs_review`, an
  unrecognized email template means `unsupported`.
- The public RSVP page must never reveal whether a name is on the guest
  list (enumeration safety). Dormant in v1; binding when links return.
- The Wedding, not the user, is the top-level unit; the couple shares full
  access to one ledger.
- **보증인원 is the venue's number, never ours.** We never recommend it, and
  never adjust counts statistically — the headcount sums real responses and
  the couple's own expected values, nothing else. `Wedding` stores the
  contracted figure so the screen can show estimate against guarantee.
  **유아식 does not adjust that number either** (decided 2026-08-11,
  `notes/2026-08-11-decision-deletion-and-infant-meals.md` §B, closing the
  question open since 2026-08-06). Children are priced differently by most
  venues but not all, and we know a venue's child pricing exactly as well as
  we know its buffer — not at all. So 유아식 is neither added nor subtracted;
  it shows as **its own count beside** the 식대 인원, and the couple applies
  their own contract. Deciding it globally would hand half our couples a wrong
  number, and a wrong number here is money. The mechanism already exists —
  it is the meal-type breakdown (`#18`), which this makes **not PC-rail-only**:
  if 유아 인원 is how a couple reads their contract, it has to be reachable on
  mobile.
- **Couple entry is the primary intake path**, not a fallback — attendance
  normally reaches them via parents and KakaoTalk. Setting attendance in the
  ledger must stay a one-or-two-tap action.
- **Meal types are configured per Wedding, not fixed by us** — venues differ
  (유아식, 글루텐프리, 뷔페 with no distinction at all). Default is a single
  type, so the simple case configures nothing. A type in use cannot be
  deleted. Dietary needs are meal types, never a separate field.
- Meal is a party-level boolean on a response but **per-type integer counts**
  on the ledger. Responses give the total; the couple distributes it across
  types. That asymmetry is where all meal detail lives.
- Accessibility needs (휠체어 etc.) are a **guest attribute**, free text —
  they belong to the person and carry forward to seat assignment later.
- **Postgres enum types only where the value set is closed forever** — `side`
  qualifies; `group_category`, `source`, `status`, `provider` do
  not. Use varchar plus application-level validation, so adding a value is a
  deploy and not an `ALTER TYPE`. The guest-group list changed twice in one
  day; assume it changes again. (`guest.lifecycle` was on this list and is not
  in v1 at all — decided 2026-08-11, returns with the RSVP links; the varchar
  rule binds it whenever it does.)
- **Every mutation response carries the recomputed aggregate**, and the client
  handles out-of-order responses. Forced by "one screen" + "all computation
  server-side": a number lagging the tap by 100ms is fine, a number moving
  backwards is not.
- **Import matching loads the wedding's guests once and matches in memory.**
  It is the only v1 operation that is easy to get badly wrong.
- **Every wedding-scoped aggregate root carries `wedding_id`**; anything
  reached only through its root does not. So `GuestChange` has it (queried
  independently). This
  binds tables that don't exist yet — seating and 축의금 arrive as roots and
  carry it from the start. It is what makes "every wedding-scoped root
  filters on `wedding_id`" mechanically checkable instead of a per-query
  judgement, and a cross-wedding leak is not an ordinary bug here.
  **Amended 2026-08-11** (`notes/2026-08-11-decision-baseline-schema-calls.md`):
  a `wedding_id` present **for integrity is not a root marker**.
  `guest_meal_count` was the stated example of a table without one and now
  carries it — because `meal_type_id` arrives in a request body and so bypasses
  `CurrentWedding`, and a row joining one wedding's guest to another's meal type
  inserted cleanly. Composite FKs to `guest (id, wedding_id)` and
  `meal_type (id, wedding_id)` make that row *unrepresentable*; the table is
  still not a root. The distinction stays checkable rather than arguable — an
  integrity-purpose column appears in a composite FK to a parent's
  `(id, wedding_id)`, a root's does not — and `#80`'s allowlist test carries it
  as an explicit exception. **An integrity `wedding_id` is an FK component and
  never a query predicate**: `select sum(expected_count) from guest_meal_count
  where wedding_id = ?` counts soft-deleted 하객's meals, because
  `@SQLRestriction` cannot reach a native query — it does not throw, it
  over-counts, and over-counting 보증인원 is money. Every read joins `guest` and
  filters `guest.deleted_at`. Held by `GuestMealCountSchemaTest`.
- **Every delete is soft** (decided 2026-08-10,
  `notes/2026-08-10-decision-soft-delete.md`) — but only on rows a *user* can
  delete. `guest`, `membership`, meal type and `wedding` carry `deleted_at`;
  `guest_change` and the import/ingest records do not, because a deletable
  audit log is not an audit log and deleting an ingest breaks hash idempotency.
  Three consequences bind everything downstream:
  **(1)** `@SQLRestriction` filters the JPA path and **does not touch native
  queries** — so the one path the automatic filter cannot reach is the native
  aggregation that computes 보증인원. A missed filter there does not throw, it
  over-counts, and over-counting is money. It is closed by a test (`#17`), not
  by attention. The default is on precisely because forgetting it then shows
  *fewer* rows rather than leaking deleted ones.
  **(2)** The import matcher is the one path that must *see* deleted rows —
  it cannot ask "되살릴까요?" about a row it cannot load. That is an explicitly
  named bypass, never an ambient one.
  **(3)** Every unique constraint becomes a **partial** index
  (`WHERE deleted_at IS NULL`), or a dead membership blocks a re-invite.
- **A deleted guest reappearing in an import is asked about, not skipped** —
  되살리기 / 그대로 두기, and "그대로 두기" is remembered under the standing
  *"a resolved question is not asked again"* rule. This is a **deliberate
  exception** to "a returning file is a stale name list": attendance has a
  screen to fix it on and deletion does not, so the import is the only place
  the couple will ever be told that guest exists. Attendance itself is
  unaffected — import still never touches it.
- **The session never knows the wedding.** Each request resolves
  user → membership → wedding; one person may belong to several.
- **Guest groups are seven fixed categories plus a free label**: 가족 · 친척 ·
  사촌 · 혼주 손님 · 친구 · 직장동료 · 기타. Aggregation splits by category
  only — free labels fracture on typing variants. The categories ship as a
  **dropdown in the .xlsx template** so a parent classifies at the moment they
  know; but data validation is advisory and gets defeated, so **the importer
  never assumes the column is clean**. **A row whose 관계 is not one of the
  seven does not import** (decided 2026-08-11,
  `notes/2026-08-11-decision-import-row-rejection.md`) — the rest of the file
  does, and the couple fixes those rows and uploads again. **Rejection is per
  row, never per file**, and the rule is stated on the row, so an empty 이름
  and a non-positive 참석 인원 are the same case. That record **supersedes the
  shipped synonym table, the map-by-distinct-value screen, and "unmapped
  imports as 기타 with the raw text in the free label"** — all three answered a
  question nobody asks, because **the couple is a reviewer, not a courier**:
  they open the file before uploading and can classify their own 이모. It does
  not dent *"not sure" must never block*, which differs in the one way that
  matters — whether anyone can answer. Identity ambiguity has nobody (so never
  block; merge later, losslessly); a malformed 관계 has the couple, holding the
  mouse. The notice beside the upload sets that expectation and cleans nothing:
  the person who reads it is the couple, while the person filling the column is
  a parent who never sees our screens — which is exactly why the .xlsx dropdown
  stays. Family is one bucket
  deliberately: a finer list keeps producing members that fit nowhere
  (조부모 was the first), and every family category is single digits while
  혼주 손님 / 친구 / 직장동료 run to a hundred.
- **Import is a workflow, not an upload.** We hand the couple a template
  (**이름 · 관계 · 참석 인원 · 연락처 선택** — deliberately no attendance
  column), they distribute it to both sets of parents, and files come back
  several at a time.
- **Import is idempotent, and the file has no opinion about attendance.**
  A matching file hash is not processed at all; rows identical in every field
  are skipped silently; only 인원수 can actually conflict. **Import never
  touches an existing guest's attendance** — a returning file is a stale name
  list, not a fresh claim that everyone on it is coming.
- **Never overwrite the couple's edits — alert and let them choose**, guarded
  by two rules that keep it quiet: **silence is not disagreement** (a blank
  cell or absent column is not a claim, so it raises nothing) and **a resolved
  question is not asked again**. Comparison is by multiset, not row: two
  guests may legitimately share a name and a group.
- Conflicts go to one review screen (never a modal per row), where the
  **summary is the screen and the conflict list is the appendix**. Each
  question has exactly two buttons. **"Not sure" imports as a separate guest
  rather than blocking**, because merging is lossless.

## Product values (apply to every decision)

These two values are the project's standing test for any feature, design,
or code decision:

1. **정직함 · 믿음직함** (honest, trustworthy) — premium-service trust.
   Guest contacts and, later, 축의금 money data are sensitive: security,
   privacy, and never-wrong numbers are requirements, not polish.
2. **깔끔하되 핵심은 다 있게** (clean, yet nothing essential missing) —
   fewer things, each complete. When in doubt, cut scope, not quality.

## Do not reference the prior attempt

This project restarts an earlier wedding-related service, archived at
`archive/experiments/2026-07/wedding-management`. Do not read, port, or take
design/architecture cues from that archive — this is a deliberate fresh start,
not a continuation. If historical context is needed, ask the user directly
rather than inspecting the archive.

## Naming

Brand name: 동행 (Donghaeng) — "walking together," chosen to express the
service acting as a steady companion through the couple's wedding journey.
Repo/folder slug: `donghaeng`.

## Agents (set up 2026-08-07, `explainer` added 2026-08-08)

Five subagents in `.claude/agents/`, split by role:

- **`backend-implementor`** — all `api/` code. Sole owner of
  `docs/api-spec.md`.
- **`frontend-implementor`** — all `web/` code. Reads the spec, never `api/`
  source.
- **`reviewer`** — correctness, the domain question, convention and the
  refactoring gate. Read-only.
- **`security-manager`** — audits against
  `notes/2026-07-30-decision-network-security.md`. Read-only.
- **`explainer`** — the comprehension gate at the end of a stop. Writes the
  Background/Intuition/Code/Quiz document, in Korean, as a self-contained
  HTML file outside the repo. Never touches repo code, and never carries the
  implementation session's context.

Five things bind:

- **Delegation is automatic in this repo.** Route implementation work to the
  implementors and review work to the reviewers without being asked; explicit
  invocation also works. This overrides any general default about not spawning
  agents unprompted.
- **The API contract is a shared asset, not a backend artifact.**
  `docs/api-spec.md` is written by the backend **in the same change as the
  code** — new, changed, and deprecated alike, never as a follow-up — and the
  frontend builds against it without reading `api/`. When the spec is silent
  or wrong the frontend stops rather than guessing, and never computes a
  number client-side to route around it.
- **The reviewers cannot write.** No `Edit`, no `Write` — that is what makes
  their verdict worth reading. They report; the implementors fix. `explainer`
  has `Write` for exactly one purpose — its own HTML file, in the
  scratchpad — and never for repo code.
- **`explainer` runs last and cold.** After the review findings are
  resolved, never before: an explanation of code that is about to change is
  wasted reading. It gets the diff and `notes/`, never the session that
  produced the code — see **Development tempo** for why that separation is
  the whole point.
- **This file and `notes/` stay the single source of truth.** An agent prompt
  carries a short operative checklist of the rules its own area actually
  breaks — an auto-delegated agent gets one shot, so the reminder earns its
  place — but the prompt is never where a rule is decided. Every agent reads
  this file first, and when a rule changes here, sweep `.claude/agents/` in
  the same change.

## Rules

- Language: this file, README.md, notes/, code comments, and scripts are
  English (per workspace convention in the root AGENTS.md). In-app
  user-facing copy will be Korean once built.
- Full engineering discipline applies here per workspace rules for
  `products/`: git repo, README, AGENTS.md/CLAUDE.md, tests — tests apply
  once there is code to test.
- Decisions from alignment conversations are recorded as dated files in
  `notes/` and reflected here when they change standing rules.
