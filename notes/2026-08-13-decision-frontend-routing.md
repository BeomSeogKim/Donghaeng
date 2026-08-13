# Decision — the router, and the shape of the login screen (2026-08-13)

Closes `#43` (router choice and wiring), which `#2` deliberately left out because
a route table written when there are no screens is a table of invented routes.
Decided while building `#38`, the first two real screens, as that issue said it
would be.

## React Router's data APIs are not used. Plain `<Routes>`, and React Query owns
## every byte of server state

The fork `#43` named: loader/action, or a plain route table with the data layer
untouched.

**A loader is a second place that fetches**, and this app already has a first
one that it cannot give up: `notes/2026-08-08-decision-frontend-architecture.md`
puts server state in React Query, and the mutation-writes-the-aggregate
mechanism — a tap moves the headcount with no second round trip — *is* the
cache. Adding loaders would not replace that; it would sit beside it, and the
two would hold the same number. That is the one failure this product may not
have.

The concrete costs of adding them, in this app:

- **The ledger is one screen the couple stay on.** Loaders pay off when
  navigation is the dominant way data changes. Here the dominant way is a tap
  that mutates in place — `notes/2026-08-07-design-screens-and-flow.md`: "there
  is essentially one screen".
- **A loader runs outside React**, so anything it needs from the cache goes
  through `queryClient` reached from module scope — a second wiring, and one
  that has to be rebuilt for tests.
- **Nothing here needs the router to fetch.** The session query is shared by the
  guard and by the screen through the same cache entry, which is what a loader
  would have been for.

Revisit only if a route ever needs data *before* it renders in a way React
Query's `ensureQueryData` cannot express. None does.

## The session gate sits above the route table

`useSession()` resolves once in `App`, and nothing renders until it answers:
pending is a brand-mark screen, a non-401 failure is its own screen, and only
then does the table exist. **A screen that shows the login button to someone
already signed in, or the ledger to someone who is not, has already leaked the
wrong thing** — even for one frame.

The guard itself is a ternary per route because there are two routes. **It
becomes a layout route rendering an `<Outlet>` when there is more than one
protected screen**, which is a mechanical change; writing it now would mean
inventing the nesting it protects.

## A 401 is not an error, and a 500 is not a logout

`GET /auth/me` answering 401 resolves to `null` — signed out — and never throws.
Every other failure throws and gets a screen that says so. **The distinction is
the product value, not tidiness**: showing the login screen when the server is
unreachable sends a signed-in person back through Google to fix a problem that
was never theirs, and tells them something we do not know.

## "마지막에 쓴 provider 기억" is deferred to `#89`, deliberately

It is written in `#38` and in the screen list, and it is not built. With one
provider there is nothing to remember: no second button to promote, no wrong
guess to cost anything, and no way to see whether the behaviour is right. It
would ship as a write to storage that nothing reads. **It becomes real work, and
gets a real design, in the same change that adds 카카오 and 네이버.**

## Still open — and it is the backend's, not ours

**A failed OAuth callback is a screen we cannot reach.** `docs/api-spec.md` says
`#38` decides what to do when the callback answers 401
(`OAUTH_LOGIN_DENIED` — consent refused — or `OAUTH_LOGIN_FAILED`). The answer
is that **the frontend can do nothing at all**: the failure is rendered by the
browser as a problem+json document on the API's origin, in a navigation none of
our code is running in. There is no handler to write and no state to recover.

Fixing it means the callback redirecting failures back to the configured
frontend origin with an error the app can read — which is a backend change, and
one the spec currently rules out in its present form ("never a redirect carrying
`?error` in a query string"). Filed as a question for the backend rather than
answered here.

**Also open: the frontend's one hand-written API shape.** `#39` (OpenAPI →
generated TS) is not wired, so `web/src/lib/api-shapes.ts` carries the `/auth/me`
response by hand, in one quarantined file named after the problem. It is the
only one, and it is deleted — not edited — when `#39` lands.
