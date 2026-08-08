# Donghaeng (동행)

Wedding-journey companion web service for couples — centered on a guest
ledger (하객·좌석·축의금). See README.md for the product pitch.

## Pick up here (last session: 2026-08-08)

**Design is done.** Screen and flow design (③) — the last blocker — is
complete: `notes/2026-08-07-design-screens-and-flow.md`. The import knot that
had blocked it dissolved the same day
(`notes/2026-08-07-decision-import-idempotency.md`), and the design system is
built (`notes/2026-08-07-design-system.md`).

**Both implementors now have a decided build methodology.** Backend got
TDD/architecture/API-conventions on 2026-08-07; frontend closed the
methodology gap left open by that note on 2026-08-08 — see **Frontend
development methodology** and **Frontend architecture** below. The founder
does not have strong existing frontend instincts, so this was decided
explicitly as an enforced default rather than left to per-session judgment,
backed by a same-day 123-source research pass.

**The next step is scaffolding `web/` and `api/` — do not start it without
the user.** The four working agents are already in place (see **Agents**
below), so the scaffolding is what they have been waiting on, not the other
way round.

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

Also still open, all small: where the import file hash lives, the initial
contents of the 관계 synonym table, `GuestChange` retention, and whether
유아식 counts toward 보증인원 (needs a real venue contract).

Working style for this project: **talk design through, don't hand over option
menus.** The founder is the domain owner, and the biggest corrections have all
come from domain facts that could not be derived from these notes — 보증인원 is
the venue's number, attendance arrives via parents and KakaoTalk, the real
import risk is re-uploading the same file, the parents' sheet lists attendees
and so never states attendance. State a read, ask one open question, converge.
Reserve multiple-choice for operational forks.

## Status

Concept stage, aligned (as of 2026-08-07). Vision, core scope, product
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
`2026-08-08-decision-frontend-architecture.md`). Read them newest-first: the
2026-08-06 records supersede parts of nearly every earlier note — including
each other — and every affected note carries a banner saying what changed.
There is no application code yet — `design/` is substrate, not
implementation. **Design has no remaining blocker**: the next step is
scaffolding `web/` and `api/`. Success criteria are deliberately deferred
until after the MVP is built. Do not start implementation without the user.

## Stack (decided 2026-07-30)

Separated frontend and backend, per `notes/2026-07-30-decision-tech-stack.md`:

- `api/` — Kotlin + Spring Boot (JDK 21, Gradle KTS), JSON API only.
  Spring Data JPA + Flyway, PostgreSQL. JUnit 5 + Testcontainers.
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

- **Packages are domain-based** (`wedding/`, `guest/`, `import/`, `auth/`),
  each self-contained with its own Controller/Service/Repository/Entity —
  not layer-based (`controllers/`, `services/`, `repositories/`).
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
  unauthenticated, 403 wrong wedding, 500 unhandled (masked per security
  posture).
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
  width shifts as it counts reads as unstable.
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
- Injection risk lives exactly in the native aggregation queries; column
  names go through a whitelist, never string concatenation.
- Parsed vendor email is rendered as text, never as HTML.
- Rate limits are per wedding, and per link token once links exist —
  **never IP-only** (Korean carrier NAT would block real guests).
- Actuator is never internet-exposed; SSH only via Tailscale.
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
  qualifies; `group_category`, `lifecycle`, `source`, `status`, `provider` do
  not. Use varchar plus application-level validation, so adding a value is a
  deploy and not an `ALTER TYPE`. The guest-group list changed twice in one
  day; assume it changes again.
- **Every mutation response carries the recomputed aggregate**, and the client
  handles out-of-order responses. Forced by "one screen" + "all computation
  server-side": a number lagging the tap by 100ms is fine, a number moving
  backwards is not.
- **Import matching loads the wedding's guests once and matches in memory.**
  It is the only v1 operation that is easy to get badly wrong.
- **Every wedding-scoped aggregate root carries `wedding_id`**; anything
  reached only through its root does not. So `GuestChange` has it (queried
  independently) and `GuestMealCount` does not (lives inside `Guest`). This
  binds tables that don't exist yet — seating and 축의금 arrive as roots and
  carry it from the start. It is what makes "every wedding-scoped root
  filters on `wedding_id`" mechanically checkable instead of a per-query
  judgement, and a cross-wedding leak is not an ordinary bug here.
- **The session never knows the wedding.** Each request resolves
  user → membership → wedding; one person may belong to several.
- **Guest groups are seven fixed categories plus a free label**: 가족 · 친척 ·
  사촌 · 혼주 손님 · 친구 · 직장동료 · 기타. Aggregation splits by category
  only — free labels fracture on typing variants. The categories ship as a
  **dropdown in the .xlsx template** so a parent classifies at the moment they
  know; but data validation is advisory and gets defeated, so **the importer
  never assumes the column is clean**. Unmapped values are asked **by distinct
  value, not by row** ("이모" ×40 is one question), pre-filled from a **static
  synonym table we author and ship** — a dictionary, not inference, and not
  learned from anyone's data. Unmapped still imports, as 기타 with the raw text
  kept in the free label. Family is one bucket
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

## Agents (set up 2026-08-07)

Four subagents in `.claude/agents/`, split by role:

- **`backend-implementor`** — all `api/` code. Sole owner of
  `docs/api-spec.md`.
- **`frontend-implementor`** — all `web/` code. Reads the spec, never `api/`
  source.
- **`reviewer`** — correctness, the domain question, convention and the
  refactoring gate. Read-only.
- **`security-manager`** — audits against
  `notes/2026-07-30-decision-network-security.md`. Read-only.

Four things bind:

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
  their verdict worth reading. They report; the implementors fix.
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
