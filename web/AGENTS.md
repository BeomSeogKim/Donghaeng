# web/ — frontend rules

React + TypeScript + Vite, built to static files. **The root `AGENTS.md` still
binds** — this file adds what only applies inside `web/`, and never
contradicts it. `notes/` remains the single source of truth for *why*; every
section below names its record.

**The design system's own rules are `design/AGENTS.md`** — read it too, and
before writing any component. This file carries only how that system is
*consumed*: the `@theme` bridge, the value checker, and the Tailwind-specific
consequences.

- **v1 ships one bundle** (the couple app); the separate guest RSVP bundle
  arrives with the RSVP links. The rule holds whenever they land: **a guest
  must never download the couple app's code.**
- **Web and mobile are two layouts, one codebase** (reaffirmed 2026-08-07).
  Shared: one route, one data layer, one token set. Split: the layout and
  `GuestRow`. Hold that line — the moment the same number is computed twice,
  the two versions can disagree about it. **PC earns its existence through the
  aggregation rail** (group and meal breakdowns) and the contact column, not
  through being wider; without them it is a wide phone screen.
- **`docs/api-spec.md` is the source of truth for the API.** Build against it
  without reading `api/`. When the spec is silent or wrong, **stop** rather
  than guessing — and never compute a number client-side to route around it.

## Development methodology (decided 2026-08-08)

`notes/2026-08-08-decision-frontend-testing-methodology.md`. The same three
gates as the backend, scoped to where frontend risk actually concentrates
rather than applied to every component:

1. **Red Gate** — an integration test written before the component/hook
   exists, confirmed failing for the right reason.
2. **Blue Gate** — the minimum implementation that turns it green.
3. **Green Gate** — refactor with the suite green throughout.

**Mandatory for**: the ledger/headcount/meal-count display, every mutation
flow (attendance tap, guest edit, CSV import, vendor-email conflict
resolution), and anything branching on the API's error `code` field. **Not
mandatory for** static layout, one-off screens, or logic-free display
components — chasing coverage there is cost with no return.

Default to **integration tests** (Vitest + React Testing Library, rendering
the real component) over isolated unit tests; mock only the network boundary,
with **MSW** — never the app's own data-layer module or hooks. Query by
role/label/text first, `data-testid` only when nothing semantic works.
**Playwright** stays thin: 2-5 true cross-page critical flows, never a
coverage target.

## Architecture (decided 2026-08-08)

`notes/2026-08-08-decision-frontend-architecture.md`.

- **Folder structure starts flat** (`src/{components, pages, lib, hooks}`) and
  escalates to `src/features/<name>/` only once a feature's files are actually
  scattering — not before. Feature-Sliced Design's full six-layer taxonomy is
  explicitly rejected for this team size. **No barrel files**, ever; never
  reach into another feature's internals.
- **Server state — anything that's a client-side copy of API/DB data — goes
  through React Query (TanStack Query)**, never a hand-rolled `useEffect` +
  `fetch` + `useState`. A mutation's `onSuccess` writes the response straight
  into the query cache — this is the mechanism behind "every mutation response
  carries the recomputed aggregate."
- **Client state escalates one rung at a time**: `useState` → lift to the
  common parent → `useReducer` → Context (wrapped in a custom hook,
  immediately) → a client-state library, only once Context is provably
  struggling. Never start at Context or a library.
- **Hooks, not containers.** No `XContainer`/`XView` split; extract
  data-fetching/derivation/subscription logic into a custom hook. This is the
  mechanism "web and mobile are two layouts, one codebase" runs on.
- **`useEffect` is for external sync only.** Before writing one, name the
  outside-of-React thing it synchronizes with. A user action (a tap, a submit)
  belongs in that action's event handler, never an Effect watching a trigger
  flag.
- **The router is a plain route table; React Router's data APIs are not used**
  (decided 2026-08-13, `notes/2026-08-13-decision-frontend-routing.md`). A
  loader is a second place that fetches, and server state has exactly one home.
  The session resolves *above* the table, so no screen renders before "am I
  logged in?" is answered.
- **Every call to the API goes through `src/lib/api.ts`**, which is the only
  place `credentials: 'include'` is written. A bare `fetch` sends no cookie, and
  the API answers 401 — which reads as "not logged in", not as the bug it is.

### The token bridge (built 2026-08-08)

`web/src/index.css` **imports** `design/tokens.css` rather than copying it, and
every `@theme` entry is a `var()` reference, never a literal — so utilities
resolve to tokens at runtime and the dark theme's `:root[data-theme="dark"]`
overrides flow through for free. Each Tailwind namespace is cleared to
`initial` first, so **`bg-slate-100` and `rounded-lg` do not exist.**

Note the limit, which is real: the *named scale* is dead, but arbitrary-value
syntax (`bg-[#ff0000]`, `text-[13px]`) still compiles. That gap is not the
bridge's to close — it is closed by the checker below.

### The design-value checker (built 2026-08-10)

`notes/2026-08-10-decision-design-value-enforcement.md`.
`web/scripts/design-values.mjs`, run as `npm run check:design` inside
`npm run lint`, in CI.

- **It is hand-written because the rule cannot be expressed in the linter.**
  Biome's only extension point is GritQL over Rust's `regex`, which has no
  lookaround, and the rule needs "flag `[...]` *unless* its content is
  `var(--dh-*)`" so that `border-[var(--dh-gold)]` passes. **A checker next to
  a linter looks like duplication and is not; do not try to fold it in.**
- Biome itself was likewise not a preference — `typescript@^7` is the native
  port with no compiler API, so typescript-eslint cannot parse this repo at
  all. Known cost: Biome has **no type-aware rules**, so a floating promise in
  a mutation handler is caught by nothing (`#71`).
- **Only design-carrying prefixes are checked; layout arbitrary values pass**
  (founder's call, 2026-08-10). `grid-cols-[1fr_auto]`, `z-[60]`,
  `content-['']` are legal — they are not colour, size, radius or duration. A
  prefix earns its place on the list **only if `design/tokens.css` has a token
  family behind it**, which is what makes membership checkable instead of
  arguable. Viewport units are a value-shape exception (`min-h-[100dvh]`
  passes) because they are a relationship to the device, not a step on a
  scale — but the match is anchored, so `min-h-[calc(100dvh-44px)]` still
  fails on the hardcoded tap floor.

## Consuming the design system

**The system itself — the thesis, the palette, contrast, typography, the
component inventory — is `design/AGENTS.md`. Read it before writing a
component; it is not repeated here.** What follows is only what changes when
those rules meet Tailwind.

- **Gold is not a Tailwind colour utility** (decided 2026-08-08). Deliberately
  absent from the `@theme` bridge's `--color-*` namespace, so **`text-gold`
  does not exist**; the hairline, meter and brand mark reach for
  `var(--dh-gold)` directly. Gold's contrast inverts between themes, so the
  identical utility would be correct in dark and unreadable in light — **the
  one case a token name provably cannot warn you about**, which is why the
  utility is withheld rather than documented.
- **Tabular figures are Tailwind's `tabular-nums` utility, not `.dh-num`**
  (decided 2026-08-08 — two mechanisms existed, and the same mandatory rule
  must not be expressed two ways).
- **A component's visual rules come from `design/components/parts/`, not from
  reading the built preview.** `dist/` is generated.

## Screen rules

The product facts these implement are in the root `AGENTS.md`. What follows is
what `web/` is required to do with them.

- **Ledger + headcount on one screen is the first fixed point of the screen
  design** — splitting them turns one action into tap → navigate → check →
  return, which is exactly what a spreadsheet with a SUM already does.
- **Search belongs on the ledger, beside the side/attendance filters** — and it
  is not the same control as them. Filters narrow a list; they do not find a
  person. The real trigger is "김영수 못 온대" and that needs two syllables, not
  a 400-row scroll — **the second most-used control after the attendance chip.**
  (Which parts these map to is `design/AGENTS.md`.)
- **유아 인원 must be reachable on mobile.** It renders as its own count beside
  the 식대 인원, via the meal-type breakdown (`#18`) — which this makes **not
  PC-rail-only**, because if 유아 인원 is how a couple reads their contract,
  they read it on a phone.
- **Setting attendance must stay a one-or-two-tap action** — couple entry is
  the primary intake path, not a fallback.
- **보증인원 does not render until it is set** in 설정 · 웨딩 정보. Onboarding
  asks for **date and names only**.
- **Parsed vendor email is rendered as text, never as HTML.**
- **Conflicts go to one review screen**, never a modal per row, where the
  **summary is the screen and the conflict list is the appendix**. Each
  question has exactly two buttons.
- **A rejected import row states its rule on the row**, and the notice beside
  the upload sets that expectation — it cleans nothing.
