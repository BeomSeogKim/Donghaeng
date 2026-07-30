# Decision — tech stack (2026-07-30)

Resolves the "tech stack" item left open in
[2026-07-26-decision-core-scope.md](2026-07-26-decision-core-scope.md).
Decided after the MVP v1 requirement breakdown and the hard-spot design
([2026-07-30-design-guest-ledger-hard-spots.md](2026-07-30-design-guest-ledger-hard-spots.md)),
so the stack is chosen against known requirements rather than in advance.

## Shape: separated frontend and backend

- **Backend** — Kotlin + Spring Boot, a pure JSON API. Runs on the VPS.
- **Frontend** — React + TypeScript SPA, built to static files. Served
  from Cloudflare Pages.
- **Database** — PostgreSQL.

The two deploy independently. This maps directly onto the workspace PROD
architecture standard in `notes/infra-zones.md` (static → Cloudflare
Pages, runtime → VPS docker compose behind Caddy, images via GitHub
Actions → GHCR, managed Postgres), so no new infrastructure primitive is
introduced.

## Why Kotlin/Spring for the backend

The product's risk is concentrated in correctness, not in UI throughput:
the meal-guarantee headcount, the RSVP response↔ledger matching state
machine, and later 축의금. "Numbers must never be wrong" is a stated
product value, and Kotlin/Spring/JPA is where the developer's judgment is
sharpest — transaction boundaries and JPA pitfalls are known territory
rather than things to learn while building the part that must not break.

## Why a static SPA for the frontend

The guest RSVP page is shared privately through a mobile invitation, so
there is no SEO requirement — which removes the main reason to run an SSR
frontend. Without SSR, the frontend build output is just static files,
needing no runtime of its own. Adding Next.js would have put a second
runtime on the VPS in exchange for a capability the product does not need.

### Two build entries

The frontend ships as **two separate bundles from one source tree**:

1. Couple app — ledger, aggregation, settings.
2. Guest RSVP page — the response form.

A guest opening the invitation link must not download the couple app's
code (ledger tables, aggregation, query layer) to see a single form. The
split keeps the guest bundle small — protecting the 30-second response
target and the first-impression quality — while design tokens and shared
components stay defined once.

## Component choices

### Backend

- Kotlin on JDK 21, Spring Boot 3.x, Gradle (Kotlin DSL).
- Spring Data JPA for persistence; **aggregation queries are written
  explicitly as native queries** rather than contorted into JPQL — the
  counts are the product, and they should be readable and verifiable.
- **Flyway** for schema migrations.
- PostgreSQL 16 (matches the DEV-zone local instance).

### Frontend

- React + TypeScript, built with Vite.
- TanStack Query for server state, React Router for routing, Tailwind for
  styling. Nothing beyond this without a reason.

### Auth

- **Couple: Kakao OAuth.** Beyond fitting Korean users, the decisive
  reason is that we then **never store passwords** — fewer credentials to
  protect is a direct contribution to 정직함·믿음직함. Email login is a
  later addition if needed.
- **Session: server-side session behind an HttpOnly cookie.** Chosen over
  JWT because sessions can be revoked immediately; a token that stays
  valid after logout is the wrong default for a product holding guest
  contacts and, later, money data. The web and API hosts sit under one
  registrable domain, so the cookie works with CORS credentials enabled.
- **Guests are never authenticated** (per the v1 requirements). The
  per-guest link carries an unguessable, single-purpose token; the shared
  link carries none.

### Testing

- JUnit 5, with **Testcontainers PostgreSQL** for repository and
  aggregation tests. Verifying counts against an in-memory database would
  not establish that the real numbers are right, which is the whole point.
- Frontend: Vitest + Testing Library, kept minimal.

## Repository layout

One repository, two directories: `api/` (Kotlin) and `web/` (React).
Separate deployables, single source of truth. Splitting into two
repositories would add coordination cost with no benefit at solo scale.

## Rejected, and why

- **Next.js full-stack (TypeScript everywhere)** — would unify the
  language and share types, but puts the correctness-critical work in the
  less-practiced stack and adds a second VPS runtime for an SSR capability
  the product does not need.
- **Server-rendered guest page (Thymeleaf)** — the fastest possible first
  paint for the RSVP form, but it creates a second frontend stack and a
  duplicated design system, which loses more than it gains under
  깔끔하되 핵심은 다 있게. The separate-bundle split captures most of the
  same benefit.
- **JWT sessions** — revocation semantics are wrong for this data.

## Still open

- [ ] Screen/flow design for the ledger, aggregation, and guest RSVP page.
- [ ] Domain model / ubiquitous language + KO↔EN glossary before code.
- [ ] Success criteria — still deliberately deferred until after the MVP.
