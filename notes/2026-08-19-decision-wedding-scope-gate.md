# Decision — how the wedding gate fails closed, and what it says out loud (2026-08-19)

`#5` builds `user → membership → wedding` resolution, the thing that actually
rejects a request now that `authorizeHttpRequests` is `permitAll` in every
environment (`2026-08-10-decision-auth-gate-and-sequence.md`). Where the resolver
lives was settled by `2026-08-17-decision-first-domain-endpoint-shape.md`; what it
answers was settled by `2026-08-10-decision-cross-tenant-status-code.md`. Three
things were left for whoever read the real code, and this records them.

## 1. The fail-closed mechanism is a build-time sweep, not an interceptor

The auth-gate record allowed either and refused neither. The sweep wins, and not
narrowly.

**An interceptor structurally cannot see the failure that is left.** `#37` closed
`fun h(caller: AuthenticatedUser)` with the annotation forgotten by matching on the
TYPE. What it did not close is an author *adding* an annotation: `@RequestBody`,
`@ModelAttribute`, `@RequestParam`, `@RequestPart` and `@PathVariable` are each
resolved by a Spring block that runs before any custom resolver, so the handler is
handed an identity built out of the request — `?id=42` — and the session is never
consulted. To an interceptor that handler *has* declared a principal. Only a
signature sweep tells the two apart, so a sweep has to exist either way.

**And an interceptor's reach would collapse onto the sweep's.** It sees every
handler in the context, ours and the framework's — springdoc's `/v3/api-docs`,
generated in the build by `#39`, and the `ERROR` dispatch — so it would need its own
exemptions for those, leaving it covering exactly what `ResolvedPrincipalTest`
covers while adding a second hand-kept registry of what is public. Its distinctive
failure mode is also worse than the sweep's: a public endpoint whose annotation
someone forgot answers 401 in production, where the sweep's equivalent is a red
check.

**The cost, stated rather than hidden:** the sweep protects `main` through CI, not
the running process. That is the standing every other gate in this repository has.

Five rules ship, each verified by writing a handler that breaks it: a handler with
no principal; a handler under `{weddingId}` with no `WeddingScope`; a principal
carrying a request-binding annotation; a binding-reachable type declaring a
principal or a `weddingId` property; and — broader, because a walk can be wrong
about what it reaches while a ban cannot — nothing anywhere holding a principal as
state.

## 2. Resolution filters `wedding.deleted_at`, and the test proves the resolver did it

The partial indexes filter `membership.deleted_at` only, so a live membership onto a
soft-deleted wedding resolved cleanly and nothing anywhere said it should not.
Resolution now checks both rows.

The trap is in the test rather than in the fix. `GET /weddings/{weddingId}` reads the
wedding, so it answers 404 to a deleted one **whether or not resolution looked** —
the first version of the test passed with the condition deleted. Every *other*
wedding-scoped endpoint reads something else, and for those the resolver is the only
thing standing between a deleted wedding and its guest list. So the test asserts the
refusal came from the resolution path, via the mark below, and it was re-verified by
deleting the condition again.

## 3. A refused resolution is marked on the request, for the log only

A cross-tenant refusal must be indistinguishable from a nonexistent id in the
response, which leaves the log as the only place the difference can survive — and
`GlobalErrorHandler` said so in a comment with nothing behind it. The resolver now
sets a request attribute on refusal and the 4xx funnel appends `wedding scope
refused` to the line it already writes.

**What it marks is "a wedding-scoped request was refused", not "this was an
attack".** It does not separate a stranger's wedding from an id that does not exist;
that needs a query on the refusal path, and the alert it feeds — a spike in
401/404/429 — does not need them separated. It does separate a refused resolution
from every other 404 the API serves, which nothing did before. Status, path and the
mark; never the body, a header, or who was asking.

## What this does not decide

- **Whether a request may carry a wedding id in a body.** It may not, and the sweep
  refuses it, but the one real case is `meal_type_id` arriving in a body
  (`2026-08-11-decision-baseline-schema-calls.md`) — an id inside a wedding, not a
  wedding id — and how that is validated belongs to the endpoint that takes it.
- **Listing the weddings a person belongs to.** There is no `GET /weddings`; the
  client keeps the id from `POST /weddings`. `docs/api-spec.md` says so rather than
  leaving the frontend to infer it.
- **Rate limiting the refusal path.** The standing unit is per wedding and per link
  token, and a refused resolution has neither — the same gap `#98` already holds for
  every other pre-auth path.

Refs `#5`, `#37`, `#123`, `2026-08-10-decision-auth-gate-and-sequence.md`,
`2026-08-10-decision-cross-tenant-status-code.md`,
`2026-08-10-decision-soft-delete.md`,
`2026-08-17-decision-first-domain-endpoint-shape.md`
