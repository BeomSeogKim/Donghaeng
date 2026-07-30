# Donghaeng (동행)

Wedding-journey companion web service for couples — centered on a guest
ledger (하객·좌석·축의금). See README.md for the product pitch.

## Status

Concept stage, aligned (as of 2026-07-30). Vision, core scope, MVP v1
boundary, product values, the MVP's three hard design spots, and the tech
stack are decided — see the decision records in `notes/`
(`2026-07-26-decision-core-scope.md`, `2026-07-26-mvp-v1-requirements.md`,
`2026-07-30-design-guest-ledger-hard-spots.md`,
`2026-07-30-decision-tech-stack.md`). There is no code yet: screen/flow
design and the domain model still come first, and success criteria are
deliberately deferred until after the MVP is built. Do not start
implementation without the user.

## Stack (decided 2026-07-30)

Separated frontend and backend, per `notes/2026-07-30-decision-tech-stack.md`:

- `api/` — Kotlin + Spring Boot (JDK 21, Gradle KTS), JSON API only.
  Spring Data JPA + Flyway, PostgreSQL. JUnit 5 + Testcontainers.
- `web/` — React + TypeScript + Vite, built to static files, as **two
  bundles**: the couple app and the guest RSVP page. A guest must never
  download the couple app's code.
- Auth: Kakao OAuth for the couple, server-side session behind an
  HttpOnly cookie. Guests are never authenticated.
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

- All tokens (session, shared link, per-guest link, invite): ≥128-bit
  CSPRNG, **stored SHA-256-hashed**, constant-time compared, masked in
  logs. Privileges and lifetimes differ per kind — the per-guest link can
  only respond as that guest, never read.
- Injection risk lives exactly in the native aggregation queries; column
  names go through a whitelist, never string concatenation.
- Parsed vendor email is rendered as text, never as HTML.
- Rate limits are per link token and per wedding — **never IP-only**
  (Korean carrier NAT would block real guests).
- Actuator is never internet-exposed; SSH only via Tailscale.

## Standing design constraints (from the 2026-07-30 decisions)

- Both RSVP channels converge on one response model and one matching
  pipeline; responses are immutable and their link to a ledger guest is a
  separate, reversible, state-carrying thing.
- Ambiguity is never guessed — 2+ candidates means `needs_review`, an
  unrecognized email template means `unsupported`.
- The public RSVP page must never reveal whether a name is on the guest
  list (enumeration safety).
- The Wedding, not the user, is the top-level unit; the couple shares full
  access to one ledger.

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
