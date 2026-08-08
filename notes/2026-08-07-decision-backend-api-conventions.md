# Decision — backend API conventions (2026-08-07)

Closes the concrete gaps flagged right after the architecture decision:
response shape, HTTP status usage, DTO naming, and tooling — the things
that get hit on day one of scaffolding rather than day thirty. The founder
asked specifically that the response/error shape be checked against actual
web-developer convention rather than invented, since it's expensive to
change once endpoints exist.

## Success responses — no envelope

A success response is the resource's own DTO, returned directly as the
JSON body. No generic `{data: ...}` wrapper.

This is deliberately the minimal option, not the "mature public API" one.
Industry practice splits into two defaults: a full envelope (`data` /
`meta`) for APIs that need pagination, request IDs, and multiple client
versions across many external consumers, and plain resource bodies for
APIs with a single first-party client and no such surface. Donghaeng is
the second case — v1 has exactly one client, no pagination (the ledger
loads whole), and no API versioning (below) — so the envelope buys nothing
an already-defined per-endpoint DTO doesn't. `GuestResponse` is already
deliberately shaped per endpoint (e.g. carrying the recomputed aggregate on
a mutation); wrapping it in a second, generic shape adds nesting with no
payer.

## Errors — RFC 9457 Problem Details, via Spring Boot's native support

Every error response is an RFC 9457 ("Problem Details for HTTP APIs",
formerly RFC 7807, same wire shape) object: `type`, `title`, `status`,
`detail`, `instance`, plus one extension member we add — **`code`**, a
stable machine-readable string (`GUEST_NOT_FOUND`,
`IMPORT_FILE_ALREADY_PROCESSED`) the frontend switches on directly, so it
never has to parse the human-readable `detail` string to decide which
Korean copy to show in a `Toast`.

This is not a bespoke shape — RFC 9457 is the standard the wider industry
has converged on for HTTP API errors, and Spring Boot 3.x (our exact
stack) supports it natively via `ProblemDetail` / `ErrorResponse`, no
extra dependency:

- `spring.mvc.problemdetails.enabled=true` makes Spring's own built-in
  exception handling (e.g. Bean Validation failures) emit RFC 9457 bodies
  for free.
- Domain exceptions map to the same shape from one global
  `@ControllerAdvice` (extending `ResponseEntityExceptionHandler`, or
  returning `ProblemDetail` directly from an `@ExceptionHandler`).

Sources checked: [Spring Framework — Error Responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html),
[Baeldung — Returning Errors Using ProblemDetail in Spring Boot](https://www.baeldung.com/spring-boot-return-errors-problemdetail).

## HTTP status — standard verb/outcome mapping

| Outcome | Status |
|---|---|
| Read (GET) succeeds | 200 |
| Resource created (POST) | 201 |
| Update / computed action succeeds (e.g. attendance tap) | 200 |
| Delete succeeds | 204 |
| Bean Validation fails on a request DTO | 400 (via `spring.mvc.problemdetails.enabled`) |
| Resource not found | 404 |
| State conflict (duplicate, stale write) | 409 |
| Not authenticated | 401 |
| Authenticated, wrong wedding/membership | 403 |
| Anything unhandled | 500, message masked per the security note |

## DTO naming and entity separation — confirmed as proposed

`XxxRequest` / `XxxResponse`, mapped via extension functions colocated in
the domain package. Entities and DTOs stay separate — an entity never
serializes directly to a response. Both already decided in
`notes/2026-08-07-decision-backend-architecture.md`; restated here because
this note is now the single place all of `api/`'s response conventions
live.

## Not versioning the API path

No `/v1` prefix. There is no plan for a second API version, and adding the
prefix later costs nothing — paying for it now is exactly the speculative
flexibility the global guidelines rule out.

## Tooling — ktlint

Applied via the standard Gradle plugin, default ruleset. Not a design
decision so much as removing a class of bikeshedding before it starts.

## Where it lives

- This note is the decision record.
- `AGENTS.md` carries the summary, under "Backend API conventions".
- `.claude/agents/backend-implementor.md` carries the operative checklist.
