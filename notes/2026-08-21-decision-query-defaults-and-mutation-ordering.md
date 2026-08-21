# Decision — the QueryClient's defaults, and out-of-order responses are prevented rather than detected (2026-08-21)

`#44`. `#2` wired `QueryClientProvider` with a bare client and deliberately left it
untuned, because tuning needs real queries. `#148` (the ledger read) and `#135` (the
add-guest sheet) are next, so the queries exist now.

Argued from the three reads this product actually has: `GET /auth/me`, the session
probe on every page load; `GET /weddings`; and `GET /weddings/{id}/guests`, the whole
ledger in one response, which **does not paginate and will not for v1**.

## Query defaults

**`retry` is once, and only when nothing answered.** A 4xx is an answer — the server
looked and decided — so `404 WEDDING_NOT_FOUND` and `400 BAD_REQUEST` are not
retried; repeating the question cannot change them and only puts a second round trip
between the couple and the screen that says so. Everything else (a dropped
connection, a 5xx) gets one retry. The predicate branches on `ApiError.status`,
which is why it could not be written before `lib/api.ts` existed.

**`staleTime: 0` and `refetchOnWindowFocus: true` are both stock and both chosen.**
They are one decision, not two: above zero, a refetch on focus or on mount is skipped
as fresh, so a non-zero `staleTime` would quietly disable the focus refetch. Two
people share one ledger by design, so the partner's edit being there when you tab
back is correct. And until `#17`, the number on the ledger screen *is* a refetch —
`GET .../guests` carries no aggregate and the mutation responses do not carry one
yet. Traffic is one couple; refetching costs nothing worth trading a stale headcount
for.

**Mutations are never retried.** `POST /weddings/{weddingId}/guests` says in as many
words that a second guest with the same name succeeds and is a second row, so a
retried create writes a person into the ledger twice.

### The retry predicate's `failureCount`, checked rather than assumed

Added 2026-08-21, after `#156` read the predicate as always-false and the `#148`
review carried that reading forward. **The predicate is correct and the retry does
run**, and the reason it looked wrong is worth writing down once so it is not read
wrong a third time.

query-core calls `retry(failureCount, error)` with the count **before** the
increment (`createRetryer` in `@tanstack/query-core@5.101.4`: `shouldRetry` is
computed, and only then `failureCount++`). So the first failure passes `0`,
`failureCount < 1` is true, the second passes `1` and it stops — exactly one retry.
Measured, not read: a probe with an instrumented predicate observes `[0, 1]` and
two `queryFn` calls.

**The behaviour test next door is what makes this safe to depend on.** "Retries a
query once when the request never reached an answer" counts requests against a real
`createQueryClient()` and asserts `2`; it was green because the retry happened, and
it goes red the day a query-core upgrade moves the increment. That is the whole
argument for asserting defaults through behaviour instead of reading the config
object back.

## Out-of-order responses: serialise, do not reconcile

The standing constraint is that every mutation on a wedding-scoped resource returns
`{resource, headcount}` and the client must handle responses landing out of order —
a number lagging the tap by 100ms is fine, a number moving backwards is not.

**The mechanism is React Query's mutation `scope`, set as a default with a single
app-wide id.** Mutations sharing a scope id run one at a time in the order they were
fired, and query-core awaits the finishing mutation's `onSuccess`/`onSettled` before
starting the next one.

Two alternatives were on the table in `queryClient.ts`'s own notes and both are
rejected:

- **Comparing a server-side monotonic marker on the response** fixes which payload
  you *display*. It does not fix which order the server *applied* the taps: two
  writes in flight can be applied in either order, so the ledger's stored state may
  not be the couple's last tap, and the display would then be faithfully showing a
  state nobody asked for. It also needs a field no endpoint returns — `GuestResponse`
  has no `version` and no `updatedAt` — so adopting it means asking the backend for
  one.
- **A client-side sequence counter** has the same flaw and adds bookkeeping.

Serialising fixes both halves, because request N+1 leaves the browser only after N
has been applied *and* its cache write has completed. It also needs **nothing from
the API**, which is the constraint this had to satisfy: `#17` is not built, the
`headcount` member is absent from every response today, and inventing its shape to
compare against would be exactly the guess the spec rule forbids.

**One id for the whole app, not one per wedding.** A default cannot be forgotten at a
call site and forgetting is silent, which a per-wedding id passed by hand would be.
The concurrency it gives up is concurrency one couple at a human tap rate never had —
and where it bites, it is right to bite: a CSV import and an attendance tap both move
the aggregate, so running them concurrently is the bug, not the feature. A call site
may pass a narrower scope; the default is the floor.

Costs, stated plainly so a later reader can weigh them:

- Two quick taps make the second number lag by two round trips instead of one. The
  standing rule permits lag and forbids backwards.
- A *hung* request holds the queue until it fails. A *failed* one does not —
  query-core releases the next mutation in a `finally`.
- It does not order this client against the partner's device, and nothing
  client-side can. That is covered by `refetchOnWindowFocus`, by the `onSettled`
  invalidation backstop below, and on the server by `#102`'s row concurrency and
  audit trail.

### The backstop, and what `#17` owes this

Serialisation orders one client. **The last word still comes from a read**: every
mutation on a wedding-scoped resource invalidates the ledger in `onSettled`. That
call lives in each mutation hook — `#135` writes the first one — and there is no
generic wrapper, because there is no such hook yet.

`#17` owes this mechanism four things, none of them structural:

1. **No version, sequence or `updatedAt` field.** Do not add one for ordering; it
   would be paid for and unused.
2. **The aggregate stays wedding-scoped or narrower** — the unit the serialisation
   assumes. An aggregate spanning weddings would break the reasoning, though not
   today's app-wide id.
3. **When `headcount` appears, it is written from the mutation's own `onSuccess`**
   into a query key, never fetched by a second request fired alongside the mutation.
   A parallel fetch lands outside the serialised window and puts the race back.
4. **The `onSettled` invalidation must not be awaited.** query-core awaits whatever
   `onSettled` returns before releasing the next mutation, so returning the
   invalidation promise would put a full ledger refetch between two taps.

## The 401 is the client's, and there is exactly one of it

Added 2026-08-21 (`#148` review). A 401 means "log in again" wherever it comes
from — never "something went wrong" and never "check your connection" — so
`createQueryClient` gives the client a `QueryCache` and a `MutationCache` whose
`onError` writes `['session']` to `null` on any `ApiError` with status 401.

**It is on the client rather than in the hooks because there were already two
answers to one status.** `useCreateWedding` handled its own 401 and the ledger read
handled none, so the ledger rendered "연결을 확인하고 다시 시도해 주세요" for an
expired session: the wrong cause, on the screen the couple live on, in a state with
no exit — 다시 시도 would 401 forever, and `refetchOnWindowFocus` only fires on a
focus event that someone sitting in the tab never produces. The hook's own handler
was deleted when the client's landed.

**Both caches, not just queries.** A read and a write are the same status with the
same meaning, and `POST /weddings` was the one call that already proved it.

**`GET /auth/me` cannot loop through it.** Its 401 is the ordinary answer for a
signed-out person and is mapped to `null` inside the query, so it never throws
(docs/api-spec.md § GET /auth/me).

## The test helper

`renderWithProviders` already existed (`#2`). It now calls **`createQueryClient()`
itself** and overrides `retry` alone afterwards, instead of hand-building a client
from `queryClientDefaults` beside it. A test running against a client the app does
not have proves nothing about the app — a mutation-flow test has to inherit the
scope or it asserts ordering the shipped client would not have given it, and once
the 401 answer moved onto the client's caches (above), a hand-built client would
have had no answer to a 401 at all while every screen test stayed green.

## What reopens this

- **`#17` landing with an aggregate that is not wedding-scoped**, or a real need to
  run two wedding-scoped mutations concurrently — then the single scope id narrows.
- **A mutation slow enough that queueing behind it is felt** (CSV import is the
  candidate) *and* a case where running it concurrently would still be correct.
- **A second, non-couple client** writing to the same ledger — the RSVP links. From
  that day the ordering is genuinely distributed and the backstop read is the only
  guarantee, which is the argument for a server marker coming back.
