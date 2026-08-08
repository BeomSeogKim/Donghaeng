# Decision — backend TDD methodology (2026-08-07)

Resolves the first item raised when aligning on `backend-implementor`'s
development methodology, ahead of scaffolding `api/`.

## Decision

Every unit of backend work goes through three gates, in order, and does not
skip ahead:

1. **Red Gate** — write a test for the requirement before any implementation
   exists, and confirm it fails, for the right reason. A test that passes on
   first run has not tested anything; a test that fails on a compile error
   or a bad fixture is not done yet.
2. **Blue Gate** — write the minimum implementation that turns the test
   green without breaking any test that was already green. Nothing the test
   doesn't ask for — no branches for cases it doesn't cover, no anticipating
   the next requirement.
3. **Green Gate** — refactor the implementation (and, when it earns its
   keep, the test) for stability and extensibility, with the full suite
   staying green throughout. Duplication, naming, and structure get fixed
   here, never during Blue Gate.

The cycle runs per requirement, not once per PR — a feature with five
behaviors is five Red→Blue→Green passes, not one large failing test
followed by one large implementation.

## Why

Backend risk here is concentrated exactly where a wrong number ships
quietly — the meal/headcount aggregation, import matching, wedding-scoped
isolation. TDD forces the test to state the requirement before any code
exists that could look correct by accident. That's 정직함·믿음직함 applied
to the build process itself, not just the shipped behavior.

## Scope

`backend-implementor` / `api/` only, for now. Frontend methodology is a
separate, unopened question.

## Where it lives

- This note is the decision record.
- `AGENTS.md` carries the summary, under "Backend development methodology".
- `.claude/agents/backend-implementor.md` carries the operative checklist —
  that agent gets one shot per invocation and reads its own prompt, not this
  note directly.

A Claude Skill was considered and rejected as the delivery mechanism:
`backend-implementor`'s tool list is `Read, Grep, Glob, Bash, Edit, Write` —
no `Agent`, no `Skill` tool — so a skill would never actually load into its
context. The agent prompt is the only thing that reaches it automatically.

## Unchanged

The existing risk-based test requirement stands as-is: wedding-scoped
queries, aggregation, import, and anything touching a token still require
JUnit 5 + Testcontainers against real Postgres. TDD decides *when* a test
is written; that rule decides *what kind*.

## Still open

- [ ] Package/architecture layering (feature-based vs layer-based).
- [ ] Exception → HTTP status mapping, DTO mapping conventions.
