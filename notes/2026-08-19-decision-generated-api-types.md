# The generated API types are committed, and CI refuses a stale one

Date: 2026-08-19
Status: decided
Issue: `#39` (closes the `web/` half)
Pairs with: `2026-08-19-decision-openapi-artifact.md`, which settles where the
document comes from. This record settles what `web/` does with it.

## What is settled

**`web/src/lib/api-types.gen.ts` is generated from `api/build/openapi.json` and
is committed.** The command is

```
cd api && ./gradlew openapi        # writes api/build/openapi.json
cd web && npm run generate:api-types
```

**`web/src/lib/api-shapes.ts` is deleted.** It existed to name the gap, and the
gap is closed. `Session` is now `paths['/auth/me']['get']['responses'][200]
['content']['*/*']`, exported from `hooks/useSession.ts` — the hook that owns
the query owns its shape.

### Why the generated file is committed when the document is not

The document describes one commit's API and is cheap to reproduce from `api/`.
The types are what `web/` *compiles against*, and every consumer of them has
neither a JVM nor a Docker daemon: a fresh clone, the `web` CI job, an editor.
Not committing them means `npm ci && npm run typecheck` cannot run without first
booting Postgres in a container, and it means the `web` job waits on the whole
backend build to discover that a component does not compile.

Committing generated output has one failure mode — it goes stale, and a stale
seam is worse than no seam because it looks checked. **That is the entire job of
the `seam` CI job**, which regenerates from the document *this commit's API
actually serves* and fails on any difference. The types are committed for the
reader and for speed; they are trusted only because a job re-derives them.

A rename in the backend therefore lands as two red checks, in order: `seam` says
the committed types no longer describe the API, and once they are regenerated
`web` says the code still asks for the old field. Neither is skippable — a red
check is never merged.

`seam` also refuses to pass when the file is untracked. `git diff` is blind to a
file git does not know about, so without that guard the check would be loudest
green exactly when the types had been deleted.

**The generator runs `--alphabetize`, and that is what makes the diff a signal.**
springdoc emits paths and schemas in scan order, which nothing guarantees is
stable across JVM runs; without sorting, a reshuffle nobody made would turn
`seam` red and teach everyone to re-run it until it passes. `--immutable-types`
is the second flag, and it only keeps what `api-shapes.ts` already declared: a
response is a value the server sent, not a thing to edit.

### The generator is pinned at `openapi-typescript@6`, and that is forced

`typescript@^7` is the native port and exposes no compiler API. `openapi-
typescript@7` declares `typescript: ^5.x` as a **peer**, npm refuses to nest a
peer, and an override that satisfied it would hand the generator a package with
no `ts.factory` to call. **v6 is the last release that does not depend on
TypeScript at all** — it emits strings — and it supports OpenAPI 3.1, which is
the only requirement the document imposes. This is a consequence of the Biome
decision (`2026-08-10-decision-design-value-enforcement.md`), not a preference,
and it reverses by itself the day `openapi-typescript` supports TypeScript 7.

`package.json` therefore carries one scoped override, `openapi-typescript →
undici: ^8`. v6 pins `undici@^5`, which carries a live high-severity advisory;
the import is top-level but only reached when the input is a URL, and ours never
is. The override dedupes onto the copy `jsdom` already brings, so it costs
nothing in the lockfile and leaves `npm audit` clean.

### What stays hand-written, and why that is not a loophole

**The error shape.** `ApiError` and `apiError` in `lib/api.ts` read `code`, and
the generated `ProblemDetail` is Spring's own schema, which has no `code` (`#66`).
The document cannot describe the one member the client branches on, so
`docs/api-spec.md` stays the definition of the error shape — meaning is what that
file is for. This is the boundary of the rule, not an exception to it: nothing
*else* in `web/` may declare an API shape, and the moment `#66` puts `code` in
the document, this comes across too.

The problem-document doubles in `App.test.tsx` are left untyped for the same
reason. The success double is not: `signedIn` is typed `HttpResponse.json<Session>`,
because a mock the API outgrew stays green forever and that is the precise
failure `#39` exists to close.

**The cast in `fetchSession` remains, and always will.** Generated types are
compile-time only; nothing has checked that the body matches. What changed is
what it is cast *to*. Validating a response at runtime is a separate decision
nobody has made.

## What is deliberately left

- **The `*/*` media-type key is consumed as written.** Correcting it is a backend
  change (`#66`) and the wrong correction — `default-produces-media-type` — would
  mislabel the error paths, which really do send `application/problem+json`.
- **No client is generated, only types.** `lib/api.ts` stays the one door,
  because it is where `credentials: 'include'` lives and a generated client would
  be a second one.
