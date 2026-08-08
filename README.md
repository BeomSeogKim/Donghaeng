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

SPRING_PROFILES_ACTIVE=dev sealbox run -p donghaeng -- ./gradlew bootRun --no-daemon
```

`bootRun` without the `sealbox run` prefix fails at startup with a datasource
error — that is the missing environment, not a broken build. `--no-daemon`
matters: a reused Gradle daemon does not reliably carry the injected
environment through to the forked application JVM.

#### Profiles

There are exactly two: **`dev`** and **`prod`**. `dev` is both what the
founder runs interactively and what the Mac mini instance will use — the DEV
zone and the development machine are currently the same computer, so
splitting them would buy nothing.

**A profile is mandatory.** Starting without one fails immediately, with an
`APPLICATION FAILED TO START` block naming the fix — not a stack trace. The
mechanism is a required `donghaeng.profile` property that only the two
profile files define. Fail-fast rather than fail-visibly, because the
visible failure (a broken OAuth `redirect_uri`) only appears at the first
login attempt, and Flyway has migrated the database long before that.

| Profile | Where | Differs from the base |
|---|---|---|
| *(none)* | — | **startup error**, by design |
| `dev` | the founder's Mac, later the Mac mini | *looser*: `com.donghaeng` at DEBUG, springdoc on — *tighter*: bound to `127.0.0.1`, pool capped at 5 |
| `prod` | VPS behind Cloudflare | *looser*: forwarded-header handling — *tighter*: pool capped at 5 |

The base holds the setting whose **wrong value is unsafe**, so a profile has
to opt out of safety on purpose: springdoc off, `ddl-auto: none`, Flyway
`clean` disabled, request-detail logging off, and the three Hibernate SQL
loggers pinned `OFF`. It is *not* the case that profiles only loosen — the
pool caps tighten, and the session cookie flags arriving with #5 will have
to tighten too.

There is no `test` profile: the Testcontainers suite gets its datasource
from `@ServiceConnection`, which outranks `spring.datasource.*`, and
everything else it needs is already the base file's value. It states the
profile marker directly instead, because the suite is not an environment.

The profile is chosen by the **`SPRING_PROFILES_ACTIVE` environment
variable**, in every environment: the command above locally, the container's
env in prod. Nothing in any yml pins a default, on purpose. The equivalent
one-off form is `./gradlew bootRun --args='--spring.profiles.active=dev'`.

**Secrets are never in a yml, in any profile** — yml carries shape, the
environment carries secrets. DEV reads them from sealbox, PROD from the
deploy platform's native store (`../../notes/infra-zones.md`).

## Relationship to prior work

This is a restart of an earlier wedding-related project, archived at
`archive/experiments/2026-07/wedding-management`. This project intentionally
does not reuse code, architecture, or design decisions from that archive —
see AGENTS.md.
