# Decision — frontend architecture (2026-08-08)

Closes the architecture half of the same 2026-08-08 research pass
(`notes/2026-08-08-decision-frontend-testing-methodology.md` covers the
testing half). Same motivation: the founder wants opinionated defaults
enforced, not left to per-session judgment, because frontend is not a
domain the founder already has strong instincts for.

## Folder structure — flat, escalate only on observed pain

Day one: `src/{components, pages, lib, hooks}`, flat. Colocate a page's own
logic next to the page rather than pre-splitting into role folders. Carve
out `src/features/<name>/` only once a feature's files are actually
scattering across `components/`, `hooks/`, and page files — not before,
and not because a future feature might need it.

Two rules apply from day one regardless of scale, because they have no
downside at any size:

- **No barrel files** (`index.ts` re-exporting a folder's contents). They
  force full-module evaluation before tree-shaking and measurably degrade
  dev-server Fast Refresh as the project grows.
- **Don't reach into another feature's internals** — import from a
  shared/`lib` layer instead.

Feature-Sliced Design's full six-layer taxonomy is explicitly rejected:
every team that tried it in earnest hit the same boundary-ambiguity cost
(is this `lib` or `model`?), and that cost is worse for a one-person
frontend with no one to amortize it against via shared review convention.

## Server state vs. client state — different problems from day one

**Server state — anything that's a client-side copy of what the API/DB
owns (the guest list, the headcount, meal counts) — goes through React
Query (TanStack Query), never a hand-rolled `useEffect` + `fetch` +
`useState`.** This is not optional: it is also what makes "every mutation
response carries the recomputed aggregate" (AGENTS.md) mechanical instead
of a discipline to remember — a mutation's `onSuccess` writes the response
straight into the query cache, so the number updates without a second
round trip and without the client computing anything itself.

**Client state — genuinely local to the browser (a form field before
submit, whether a sheet is open) — escalates one rung at a time, and only
when the previous rung's failure mode actually shows up:**

1. `useState`, local to the component. Default.
2. Two components need to stay in sync → lift to the closest common
   parent, pass down via props/`children`. Still no Context.
3. One component's own handlers get tangled → `useReducer`, still local.
4. Real prop-drilling pain (three-plus layers passing data they don't use)
   → Context, wrapped immediately in a custom hook (`useX()`), never
   `useContext` called directly from a consumer.
5. A client-state library, if it ever comes to that, only after Context is
   provably struggling — a measured re-render cost, not a guess.

Never start at Context or a state library. Given v1 is one screen, this
ladder is expected to stop at rung 2 or 3 for most of the app.

## Hooks, not containers

No `XContainer`/`XView` split. Extract data-fetching, derivation, and
subscription logic into a custom hook (`useX()`); the component that calls
it stays focused on returning JSX. This is also the mechanism the "web and
mobile are two layouts, one codebase" rule (AGENTS.md) runs on: the shared
data-layer hook is what's shared, `GuestRow` and the page layout are what
splits.

## useEffect — external sync only, never a general "run this after render"

Before writing a `useEffect`, name the outside-of-React thing it
synchronizes with. If there isn't one, it doesn't get written:

- Deriving one value from other state/props → compute inline during
  render.
- An expensive calculation → `useMemo`, not state+Effect.
- Resetting state when navigating between two records (e.g. two guests) →
  pass the changing value as `key`, not a manual reset in an Effect.
- Anything caused by a user action (attendance tap, form submit) → the
  event handler that fired it, never an Effect watching a trigger flag.
  This is the same rule the mutation-flow requirement depends on: a tap
  must move the number in the same handler that made the request, not
  through a chain of Effects reacting to each other.

The one case that genuinely belongs in an Effect: synchronizing with
something outside React — a real fetch outside React Query's own
lifecycle (rare, since React Query owns this), a non-React widget, a
browser API.

## Where it lives

- This note is the decision record.
- `AGENTS.md` carries the summary, under "Frontend architecture".
- `.claude/agents/frontend-implementor.md` carries the operative checklist.

## Still open

- [ ] TanStack Query specifically, over SWR — defaulting to TanStack Query
      now on stronger evidence in the research (Kakao Pay's Redux→React
      Query migration, Woowahan's Client/Server split), revisit only if a
      concrete limitation shows up.
