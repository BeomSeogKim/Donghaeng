# Donghaeng (동행)

Wedding-journey companion web service for couples — centered on a guest
ledger (하객·좌석·축의금). See README.md for the product pitch.

## Pick up here (last session: 2026-08-07)

The **design system foundations are built** — tokens in
[`design/tokens.css`](design/tokens.css), reasoning in
`notes/2026-08-07-design-system.md`. It was built ahead of screen design at
the founder's call, and the reasoning holds: a design system coordinates
across *time*, not only across people, and this project has one person but
many sessions.

Design work resumes at **screen and flow design (③)** — now the only thing
between the model and building. One fixed point is already decided: **the
ledger and the headcount are one screen.** The next knot is **how the import
conflict screen behaves when one file brings in dozens of rows at once.**

One question I asked and the founder has not answered yet, still worth
having: **how often does the same person actually appear in both sets of
parents' files?** A handful of relatives makes the conflict screen an edge
case; a large overlap makes it the main event of import.

Four questions are open and were parked deliberately:

1. **Does 유아식 count toward the venue's 보증인원?** Likely venue-dependent.
   If it is, `MealType` needs a `counts_toward_guarantee` flag and the screen
   shows two numbers ("총 식사 45개 / 보증인원 기준 42명"). Needs the founder
   to check a real contract.
2. **When does the couple configure meal types** — onboarding, or on demand?
3. **Retention policy for `GuestChange`.** It holds old values of personal
   data (phone numbers), which sits badly beside deleting raw vendor email
   after a bounded window.
4. **Five proposals of mine still unconfirmed** — 배려사항 as free text;
   meal types defaulting to a single type; a type in use being undeletable;
   the import conflict screen being a list rather than a modal per row; and
   "not sure" importing as a separate guest rather than blocking. All five
   are cheap and most will settle naturally during ③.

Working style for this project: **talk design through, don't hand over option
menus.** The founder is the domain owner, and every large decision on
2026-08-06 came from a domain fact that could not be derived from these notes
— 보증인원 is the venue's number, attendance arrives via parents and KakaoTalk,
the guest list is collected from parents. State a read, ask one open question,
converge. Reserve multiple-choice for operational forks.

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
`2026-08-07-design-system.md`). Read them newest-first: the
2026-08-06 records supersede parts of nearly every earlier note — including
each other — and every affected note carries a banner saying what changed.
There is no application code yet — `design/tokens.css` is substrate, not
implementation. **Screen/flow design is the one remaining blocker**, and
success criteria are deliberately deferred until after the MVP is built.
Do not start implementation without the user.

## Stack (decided 2026-07-30)

Separated frontend and backend, per `notes/2026-07-30-decision-tech-stack.md`:

- `api/` — Kotlin + Spring Boot (JDK 21, Gradle KTS), JSON API only.
  Spring Data JPA + Flyway, PostgreSQL. JUnit 5 + Testcontainers.
- `web/` — React + TypeScript + Vite, built to static files. **v1 ships one
  bundle** (the couple app); the separate guest RSVP bundle arrives with the
  RSVP links. The rule holds whenever they land: a guest must never download
  the couple app's code.
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

Rules that bind everyday work:

- **Nothing hardcodes a colour, size, radius, or duration.** Everything reads
  a token. Tailwind consumes them via `@theme` so utilities and tokens cannot
  disagree.
- **Every digit that can change in place is tabular** — headcount, meal
  counts, 축의금 later. This is 정직함·믿음직함 in typography: a number whose
  width shifts as it counts reads as unstable.
- **불참 is neutral, never red.** A guest who cannot come is a fact, not an
  error. Red belongs to destroying data only — and destructive actions always
  carry a verb, never colour alone, because 홍 and error-red are neighbours.
- **Ledger rows are flush, hairline-separated — never cards.** Per-row cards
  cost ~8px of vertical rhythm each and break scanning at 400 rows. Radius is
  for things genuinely detached: chips, buttons, sheets.
- **Body text never goes below 15px.** Hangul packs more strokes into the em
  than Latin. 13px is for metadata fragments, never sentences. Korean running
  text gets 1.65 leading, not the Latin-typical 1.5. No italics — Korean has
  no italic tradition and synthesised obliques look broken.
- **Pretendard** is the app face, for consistent Hangul/Latin metrics on the
  lines where names and numerals sit together.
- **Dark theme is defined, not shipped.** v1 is light only; the tokens carry
  both so it is never a retrofit.
- Ten components cover v1 — the inventory is in the note.

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
  only — free labels fracture on typing variants. Family is one bucket
  deliberately: a finer list keeps producing members that fit nowhere
  (조부모 was the first), and every family category is single digits while
  혼주 손님 / 친구 / 직장동료 run to a hundred.
- **Import is a workflow, not an upload.** We hand the couple a template,
  they distribute it to both sets of parents, and files come back several at
  a time — so the same person arrives twice. Conflicts go to one review
  screen (never a modal per row), and **"not sure" imports as a separate
  guest rather than blocking**, because merging is lossless.

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

## Rules

- Language: this file, README.md, notes/, code comments, and scripts are
  English (per workspace convention in the root AGENTS.md). In-app
  user-facing copy will be Korean once built.
- Full engineering discipline applies here per workspace rules for
  `products/`: git repo, README, AGENTS.md/CLAUDE.md, tests — tests apply
  once there is code to test.
- Decisions from alignment conversations are recorded as dated files in
  `notes/` and reflected here when they change standing rules.
