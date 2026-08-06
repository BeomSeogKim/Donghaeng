# Donghaeng (동행)

Wedding-journey companion web service for couples — centered on a guest
ledger (하객·좌석·축의금). See README.md for the product pitch.

## Status

Concept stage, aligned (as of 2026-08-06). Vision, core scope, product
values, the MVP's hard design spots, the tech stack, the domain model, the
headcount aggregation, and the **v1 scope cut** are decided — see the
decision records in `notes/` (`2026-07-26-decision-core-scope.md`,
`2026-07-26-mvp-v1-requirements.md`,
`2026-07-30-design-guest-ledger-hard-spots.md`,
`2026-07-30-decision-tech-stack.md`, `2026-08-03-design-domain-model.md`,
`2026-08-05-design-meal-headcount.md`,
`2026-08-06-decision-v1-scope-and-meals.md`). Read them newest-first: the
2026-08-06 cut supersedes parts of nearly every earlier note, and each
affected note carries a banner saying what changed. There is no code yet:
**screen/flow design is the one remaining blocker**, and success criteria
are deliberately deferred until after the MVP is built. Do not start
implementation without the user.

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
parsed vendor RSVP email. **Our own RSVP links (shared and per-guest) are
deferred, not cancelled**, which is why responses stay a separate object
from the ledger. No guest meets the product in v1. Full record in
`notes/2026-08-06-decision-v1-scope-and-meals.md`.

Consequence worth remembering: the review *queue* has nothing to fill it in
v1. Vendor-email matches are confirmed inline and direct entry targets a
specific guest, so the only leftover case — attendance confirmed, companion
count blank — is a ledger filter, not a screen.

## Standing design constraints (2026-07-30 → 08-06 decisions)

- Every intake channel converges on one response model and one matching
  pipeline; responses are immutable and their link to a ledger guest is a
  separate, reversible, state-carrying thing.
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
