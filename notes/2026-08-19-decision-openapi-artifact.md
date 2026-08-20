# The OpenAPI document is a test artifact

Date: 2026-08-19
Status: decided
Issue: `#39` (closes the `api/` half), `#118` (closed by the fix below)
Amends: `2026-08-08-decision-build-workflow.md` — the seam it describes now has
a file and a command.

## What is settled

**`web/` generates its TypeScript types from `api/build/openapi.json`, written by
a test that boots the application and reads `/v3/api-docs`.** The command is
`cd api && ./gradlew openapi`; `./gradlew build` writes the same file as a side
effect, because the test that writes it is also the test that asserts it.

`#39` recorded two mechanisms. The springdoc Gradle plugin was the other one, and
it runs `bootRun` — which needs a datasource strategy for a database this repo
deliberately never migrates unattended (`2026-08-09-decision-schema-ownership.md`).
A boot test already has one, on Testcontainers, so the cheaper mechanism is also
the one that does not reopen a settled question. The cost is that the contract is
a *test* artifact: a suite that does not run produces no seam.

**The generating context is the only place `springdoc.api-docs.enabled` is true.**
It is a security posture and not an oversight
(`2026-08-08-decision-scaffold-secrets-and-surface.md`); the shipped image answers
404 to `/v3/api-docs`, and CI's `docker` job holds it.

**A `@RestController` declared in test source is a `@TestComponent`, or it becomes
part of the published API.** This is `#118` — the component scan is rooted at
`com.donghaeng` and the test classes are on that classpath, so a test fixture maps
into every `@SpringBootTest` context, including the one that reads the document.
It was a live leak rather than a hypothesis: the first generated document carried
eight `/test-errors/*` paths and `/test-current-user/bare`, which `web/` would
have generated a client for. Both fixtures are now `@TestComponent` and registered
by `@Import` in the one test that owns them.

**The document's path list is asserted as an exact set**, not as "does not contain
a test path". The same assertion catches both directions — a fixture that leaked
in, and a real endpoint that never arrived because of a `@Hidden` or an unscanned
controller. A new endpoint therefore edits that list, in the same change as
`docs/api-spec.md`.

## What is deliberately left

Neither of these blocks `web/` from generating types, and both are already filed:

- **Response bodies are keyed `*/*`.** No handler declares `produces`, so springdoc
  emits the wildcard and the generated types index by it. Making it
  `application/json` is one springdoc property (`default-produces-media-type`) or
  one `produces` per handler — but the second changes content negotiation at
  runtime, and the first states a media type the error paths do not use. Left for
  `#66`, with the trip hazard written into `docs/api-spec.md`.
- **The `ProblemDetail` schema is Spring's own and carries no `code`** (`#66`), the
  one member the frontend switches on. The error shape stays defined by
  `docs/api-spec.md`, which is where meaning lives anyway.
- **CI does not hand the file between jobs yet.** The `api` job writes it; nothing
  uploads it and no job consumes it. That wiring belongs with the `web/` half,
  which is what turns a renamed backend field into a red frontend typecheck — the
  whole point of `#39`.
