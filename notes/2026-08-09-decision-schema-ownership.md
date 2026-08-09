# Decision — who owns the schema (2026-08-09)

Supersedes the line in `application.yml` that said *"Flyway owns the schema
everywhere"*, and the assumption behind issue #3 that a migration runs at
deploy. Written before the baseline schema exists, so nothing has to be
unwound.

## The call

**Flyway runs in tests only. Every DDL statement against a real database is
applied by the founder, by hand.**

The reason is the founder's, and it is about blast radius rather than
convenience: a migration that runs automatically at startup is a piece of
irreversible, unattended work happening at the exact moment an environment is
least observed. `flyway.clean-disabled` protects against one catastrophic
verb; it does nothing about a `DROP COLUMN` in a migration that looked right in
review. Taking the schema change out of the deploy path means a wrong migration
is a wrong file, not a wrong production database.

## What this actually changes

- `spring.flyway.enabled: false` in the **base**, so an environment has to opt
  in deliberately. Tests opt in; nothing else does.
- The migration SQL files stay exactly where they are and stay authoritative.
  They are what the tests build the schema from, and they are the text the
  founder applies by hand. **There is no second copy of the DDL** — that is the
  property that keeps this workable.
- `spring.jpa.hibernate.ddl-auto: validate` in `dev` and `prod` (the base keeps
  `none`, which is the safe value for an environment that has declared nothing).

## Why `validate` is not optional here

Splitting "the schema the tests build" from "the schema that actually exists"
creates a gap that nothing observes. The suite passes — it built its own
database from the migrations. Production is whatever was typed into it. Nobody
compares them, and the first symptom of a divergence would be a runtime error
on a query, in front of a user.

`validate` closes most of that at the cheapest possible moment: the app refuses
to start when its entity mappings do not match the database it just connected
to. A drifted environment fails loudly at boot instead of quietly at 2 a.m.

**Be honest about its reach — narrower than it sounds.** Hibernate 6 compares
the JDBC *type code* of mapped columns and nothing else. It does **not** compare
length, precision, scale or nullability, and it says nothing about indexes,
constraints, defaults, triggers, or columns the mapping never mentions.

Concretely, and this is the case that matters here: a hand-typed `varchar(20)`
under a `@Column(length = 255)` mapping **passes validation**, and then the
first 30-character 하객 이름 fails at INSERT, in front of a user — the exact
shape of failure `validate` is supposed to prevent. When a person types the DDL,
a wrong length is the *likeliest* drift there is, and it is in the uncaught set.

So: `validate` catches a missing or misnamed table or column, and a genuinely
different type. Everything else — including size — stays the founder's own care
at the moment of typing. (Corrected 2026-08-09, same day: the first version of
this paragraph said "tables, columns and types", which reads as though a type
mismatch of any kind is caught. Reviewer checked `AbstractSchemaValidator`; I
had not.)

## Agents may not reach a non-local database

The counterpart to "the founder applies DDL by hand" is that nothing else can.
`.claude/hooks/db-guard.sh` blocks any database client command whose target is
not loopback — a whitelist, not a blacklist of known production hosts, because
a blacklist grows a hole every time an environment is added.

This deliberately blocks the founder's *own* agent sessions from applying
production DDL. That is the point, not a side effect: the founder runs those
statements in his own terminal, where he reads them first.

An unresolvable target is refused rather than assumed local — a `PGSERVICE`
entry, a Flyway config file, or a shell variable in a host position all name
something the hook cannot see, so all three are blocked. It also errs the other
way from `merge-gate.sh`: a sentence that merely *mentions* a client with a
remote host is refused, because a false block costs a rephrase and a miss costs
a production database.

### What this hook does not cover

Both reviewers probed it, and the first version let seven shapes through
(`sudo psql`, `env PGHOST=… psql`, an absolute path, `docker exec … psql`,
`PGHOSTADDR=`, keyword/value conninfo, `flyway -configFiles=`). Those are closed
and are now the regression suite in `.claude/hooks/db-guard.test.sh`. These are
**not** closed, and cannot be by a hook of this kind:

- **An SSH tunnel or port-forward.** `ssh -L 15432:proddb:5432 vps` makes a
  production database *be* loopback. Nothing in this hook's model can tell that
  apart from the local Postgres.
- **A client it has never heard of.** Every language runtime with a driver is a
  database client — `python3 -c "import psycopg…"`, a Kotlin script,
  `./gradlew bootRun` against a remote URL, a JetBrains data source. Name
  matching loses this race asymptotically.
- **Anything not routed through the Bash tool.** The hook is wired to `Bash`
  only.
- **A script file.** `bash ./script.sh` is opaque; the reviewer bypassed the
  hook this way *during the review*, without trying to.
- **`./gradlew bootRun` against a remote URL**, deliberately. `gradlew` could be
  added to the client list in one word, and it is left out because every build
  in this repo runs it: the hook would refuse ordinary work constantly, and a
  guard that cries wolf gets disabled — which is a worse outcome than the gap.
  The guard that covers this path is `SchemaOwnershipGuard`, at startup.
- **Codex sessions.** The mechanism is Claude Code's `PreToolUse`. The rule in
  `AGENTS.md` is read by both tools; only one enforces it. Same shape as the
  merge gate's Codex hole (`notes/2026-08-08-decision-merge-gate.md`).

The honest summary: this hook stops the *casual* path — an agent reaching for
`psql` against a host it was told about — and it stops it reliably. It is not a
security boundary against a determined process, and the reason it is worth
having anyway is that the casual path is the one that actually happens.

## CI has to do by hand what it used to get for free (#55)

Only **one** of the two deploy-confidence jobs is affected, and getting this
backwards is worth guarding against.

`prod-boot` runs `./gradlew test --tests '…ProfileBootTest'`. That is a Gradle
`Test` task, so it inherits the Flyway opt-in **including in CI** — it will
build its own schema from the migrations and stay green regardless of what any
real database contains. It rehearses the committed *configuration*, never the
hand-applied *schema*. Do not read it as more than that.

`docker` runs the packaged jar with Flyway off, which is the real deploy shape.
**The moment the baseline schema and its entities land (#3), that job must apply
the migration SQL to its Postgres before starting the app**, or it fails
validation and goes red for a reason unrelated to the change under test.

(Corrected 2026-08-09, same day: this section originally named both jobs, and a
comment in `build.gradle.kts` claimed the opt-in "cannot reach CI's
`prod-boot`". Both were wrong in the same direction — they described a
deploy rehearsal that `prod-boot` does not perform.)

CI is therefore the one place where the "manual" step is mechanized — and that
is consistent, not a contradiction: CI's database is created and destroyed
inside the job, so nothing there is unattended work against data anyone cares
about.

## Two things Flyway was doing that nothing replaces

Turning it off in real environments drops more than automatic application, and
neither loss is visible until it bites.

- **Version bookkeeping.** dev and prod get no `flyway_schema_history` at all,
  so *"which migrations has this database had?"* becomes unanswerable from the
  database itself. A migration applied twice, or skipped, leaves no trace — and
  `validate` cannot see either, since both can produce a matching mapped schema.
  The revisit trigger below ("drifts twice") therefore has no detector unless
  the applied version is written down somewhere the next session can read.
- **Transactional application.** Flyway wraps each migration in a transaction
  where the DDL allows it. A hand-typed multi-statement change that fails
  halfway leaves a partial schema and no marker. **So: apply DDL inside an
  explicit `BEGIN` / `COMMIT`.** On Postgres that fully recovers the property —
  but it is now a habit rather than a mechanism, which is exactly why it is
  written here.

## The yml guarantees are file-level, and the environment outranks them

Every guard in this decision — the profile sweep, the boot tests — reads
**committed files**. Spring puts `SPRING_FLYWAY_ENABLED` and
`SPRING_JPA_HIBERNATE_DDL_AUTO` from the environment *above* every yml in the
jar, and the deploy platform's environment is a place someone edits by hand,
often while staring at a red boot. One variable there reverses this entire
decision with the whole suite still green.

So the resolved values are asserted **at startup**, in the shape of the existing
missing-profile failure, rather than only in tests. This is the same lesson
`notes/2026-08-08-decision-scaffold-secrets-and-surface.md` reached for
`sslmode=verify-full`: a property of the *running environment* cannot be
guarded by a test that reads a file.

## When to revisit

- **Hand-applied DDL drifts twice.** Once is a mistake; twice is a process
  telling you it does not fit. At that point the answer is a reviewed migration
  run through a deploy step, not more care.
- **A second person can deploy.** The whole trade rests on one person holding
  the schema in his head and typing it himself.
- **The schema starts changing weekly.** Manual application scales inversely
  with frequency.
