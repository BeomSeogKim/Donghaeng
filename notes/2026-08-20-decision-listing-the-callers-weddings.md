# Decision — listing the caller's weddings, and how a scopeless wedding endpoint is declared (2026-08-20)

`#132`. Until now the only way to learn a `weddingId` was the 201 from
`POST /weddings`, which meant the client could not decide the "최초 1회" branch in
`로그인 → [웨딩 만들기 · 최초 1회] → 원장` (`2026-08-07-design-screens-and-flow.md`)
and lost the ledger on a refresh. `GET /weddings` answers both from the session.

Three things were open when the code was written, and this records them.

## 1. A list, and a scopeless endpoint is now a category rather than an exception

A person may belong to several weddings (root `AGENTS.md`), so the response is an
array even though v1's screens guide a couple to make exactly one and give them no
way to make a second on purpose. **The API may not claim singularity it does not
have**: an endpoint returning a single object would change shape on the seam the
first time it mattered, and the frontend regenerates its types from that shape.

An empty array is the ordinary answer, not a 404. The 404 this API serves for a
wedding is deliberately one answer for four situations
(`2026-08-10-decision-cross-tenant-status-code.md`); overloading it with a fifth that
a client is *supposed* to branch on would undo that.

**An anonymous request is 401 and never an empty array.** They are the same document
to a client otherwise, and `authorizeHttpRequests` is `permitAll` in every
environment — the caller is the only thing standing in front of this endpoint
(`2026-08-10-decision-auth-gate-and-sequence.md`).

**Newest first, and the order is contract.** `web/` opens `[0]`, so a database-chosen
order would open a different ledger after a refresh. It is explicitly *not* a claim
about which wedding is "current" — switching weddings and remembering the last-viewed
one are both out of scope, and when they arrive they will need a real answer rather
than an ordering that was convenient.

## 2. The scopeless exemption is written down, not inherited from the path

`#5`'s sweep refuses a handler under `/weddings/{weddingId}` that takes no
`WeddingScope` (`2026-08-19-decision-wedding-scope-gate.md`). `GET /weddings` has no
path variable, so **that rule does not catch it — and does not exempt it either.**
Falling outside a gate is not the same as passing it, and "a wedding endpoint with no
scope" is exactly the shape the gate exists to refuse. Verified rather than assumed:
a `GET /weddings/search` handler taking only an `AuthenticatedUser` was added, and
`ResolvedPrincipalTest` and `CurrentUserParameterTest` both stayed green.

So `ScopelessWeddingEndpointTest` ships beside them: **every handler Spring maps
under `/weddings` takes a `WeddingScope`, or is named in a two-entry list with its
reason** — `WeddingController.create`, which makes the first membership, and
`WeddingController.list`, whose membership join *is* its scope. It reads
`RequestMappingHandlerMapping` for the reason `#5` earned the hard way: a class-level
mapping on a base controller is invisible to a private model of Spring's routing.

**A list in a test rather than an annotation on the handler, on purpose.** An
annotation travels with a copy-pasted method; a name in an allowlist does not. The
only way to make a sixteenth endpoint scopeless is to edit a test, in a diff a
reviewer reads, and say why. A second rule in the same file requires a scopeless
wedding endpoint to still take an `AuthenticatedUser`, so making one anonymous means
editing two allowlists in two files.

**Cost, stated rather than hidden:** it is a second sweep file where one would do,
and a second Spring context in the suite. It sits beside `ResolvedPrincipalTest`
rather than inside it because `#5` was under review and unmerged; folding the rule in
once `#129` lands is an improvement, not a correction, and nothing breaks if nobody
does it.

## 3. The join is the scope, and it filters both soft deletes

One JPQL join over `membership` and `wedding`, spelling out both `deleted_at is null`
conditions even though `@SQLRestriction` adds them — the same stance
`WeddingRepository` already takes, and for the same reason: the condition that
carries the gate belongs where the query is read
(`2026-08-10-decision-soft-delete.md`). A revoked membership must not keep a ledger,
and a soft-deleted wedding must not stay listed for everyone who was ever in it.
Those are two tests, because they fail in opposite directions and neither shows the
other.

The tenancy test gives its outsider a wedding of their own, per `#5`'s §2b. Confirmed
by mutation: dropping `m.userId = :userId` — every couple listing every other
couple's weddings — turns exactly that test red and nothing else.

## What this does not decide

- **Which wedding is the current one.** Out of scope in the issue; the client takes
  the first entry because v1 people have one. A person with several needs a real
  answer, and it is a design question, not an ordering.
- **Whether the ledger reloads by id or by list.** `web/` decides; the spec says the
  list is fresher than a stored id and leaves it there.
- **Paging.** No envelope, no page — a person's weddings are counted on one hand.

Refs `#132`, `#5`, `#15`, `#124`, `2026-08-19-decision-wedding-scope-gate.md`,
`2026-08-10-decision-auth-gate-and-sequence.md`,
`2026-08-10-decision-cross-tenant-status-code.md`,
`2026-08-10-decision-soft-delete.md`,
`2026-08-07-design-screens-and-flow.md`
