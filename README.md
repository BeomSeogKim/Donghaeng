# Donghaeng (동행)

A web service that walks with couples through their **whole wedding
journey** — from the start of preparation, through the day itself, and
after. Built for couples, not vendors.

## Problem

Korean wedding preparation runs on scattered tools and manual work. The
sharpest example: guest management. Couples estimate headcounts and assign
seats in hand-built spreadsheets, re-enter RSVPs that arrive by email from
their mobile-invitation vendor, and track 축의금 after the day in yet
another ledger. Meanwhile the wedding day itself — the most stressful,
least automated point — is coordinated by phone calls and people physically
walking messages between parties.

## Who it's for

Engaged couples (신랑신부), from early preparation through the wedding day
and after. Vendors, planners, and guests may become secondary users later,
but the product is designed around the couple.

## Core: the guest ledger

The heart of the product is one object — a guest ledger that gains columns
as the journey progresses:

- **Preparation** — guest list, RSVP collection, headcount estimation
- **Day-of** — seat assignment (hotel weddings especially), reception
- **After** — 축의금 records, thank-you follow-ups, a durable ledger the
  couple keeps for years

Around that core, the broader journey is in scope long-term (day-of
checklists and alerts, vendor contact hub, guest communication) — built
incrementally, never at the cost of quality and accuracy.

## Product values

1. **정직함 · 믿음직함** — honest and trustworthy, befitting a premium
   wedding service. Money data makes this an engineering requirement
   (security, privacy), not just brand tone.
2. **깔끔하되 핵심은 다 있게** — clean, yet nothing essential missing.
   Fewer things, each complete.

Brand feeling: a steady, reliable presence for a couple who's overwhelmed —
trustworthy and calm, not flashy.

## Status

Concept stage — vision and core scope agreed (see `notes/`), MVP boundary
and tech stack not yet chosen. Platform direction is a web app
(mobile-first).

## Development

### `api/` (Kotlin + Spring Boot, port 8080)

Secrets live in sealbox, never in a `.env` — `DATABASE_URL` (JDBC form, no
credentials), `DB_USERNAME`, `DB_PASSWORD` under the `donghaeng` project.
Only running the app needs them; the tests do not, because they start their
own PostgreSQL with Testcontainers.

```sh
cd api

./gradlew build                          # compile + ktlint + tests (Docker must be running)
./gradlew test                           # tests only
./gradlew ktlintFormat                   # auto-fix formatting

SPRING_PROFILES_ACTIVE=local sealbox run -p donghaeng -- ./gradlew bootRun --no-daemon
```

`bootRun` without the `sealbox run` prefix fails at startup with a datasource
error — that is the missing environment, not a broken build. `--no-daemon`
matters: a reused Gradle daemon does not reliably carry the injected
environment through to the forked application JVM.

#### Profiles

`application.yml` is the shared base and holds the **most restrictive**
settings — springdoc off, no SQL logging, `ddl-auto: none`, Flyway `clean`
disabled. Profiles only ever loosen from there, so forgetting to set one
gives you the safe behaviour rather than an unsafe one.

| Profile | Where | Loosens |
|---|---|---|
| *(none)* | anywhere, by accident | nothing — the safe base |
| `local` | the founder's Mac | `com.donghaeng` at DEBUG, springdoc on, 5-connection pool |
| `prod` | VPS behind Cloudflare | forwarded-header handling, SQL loggers pinned `OFF`, 5-connection pool |

There is no `test` profile: the Testcontainers suite gets its datasource from
`@ServiceConnection`, which outranks `spring.datasource.*`, and everything
else it needs is already the base file's default. There is no `dev` profile
either — the Mac mini has no deploy target yet.

The profile is chosen by the **`SPRING_PROFILES_ACTIVE` environment
variable**, in every environment: the command above locally, the container's
env in prod. Nothing in any yml pins a default, on purpose. The equivalent
one-off form is
`./gradlew bootRun --args='--spring.profiles.active=local'`.

**Secrets are never in a yml, in any profile** — yml carries shape, the
environment carries secrets. DEV reads them from sealbox, PROD from the
deploy platform's native store (`../../notes/infra-zones.md`).

## Relationship to prior work

This is a restart of an earlier wedding-related project, archived at
`archive/experiments/2026-07/wedding-management`. This project intentionally
does not reuse code, architecture, or design decisions from that archive —
see AGENTS.md.
