# web/ — frontend rules

React + TypeScript + Vite, built to static files. **The root `AGENTS.md` still
binds** — this file adds what only applies inside `web/`, and never
contradicts it. `notes/` remains the single source of truth for *why*; every
section below names its record.

**This file also carries the design system**, whose sources live in `design/`
at the repo root — `web/` is where the tokens are consumed, so the rules sit
here rather than in a third place.

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

## Design system (decided 2026-08-07)

Tokens in [`design/tokens.css`](../design/tokens.css), components in
`design/components/` (sources in `parts/`, built previews in `dist/` via
`python3 design/components/build.py`), reasoning in
`notes/2026-08-07-design-system.md`. The rendered library is mirrored to the
claude.ai/design project *Donghaeng Design System* — **that is a view, not a
store**: anything decided there comes back to this repo or it does not survive.

The thesis: **동행 is an instrument, not a celebration** — the thing to beat is
a spreadsheet with a SUM in the next column, so we win by being as calm as one
and requiring less work, never by being prettier. That rules out the
wedding-stationery register entirely. But **restraint is not cheapness**
(learned the hard way 2026-08-07: the first ground was `#f4f5f7`, the SaaS
default, and it did not carry the positioning at all). Premium here is carried
by **material, not saturation** — the same reason 백자 is prized for being
*almost* white.

- **Light is 백자 · 금박, dark is 나전칠기.** 유백색 ground, 먹 ink, **자적
  `#73304e`** primary (the 비빈 rank colour), gold as metal. Dark inverts to
  옻칠 ground with gold as primary. One system's day and night, not two
  designs. **v1 ships light only** — but the tokens carry both, so it is never
  a retrofit.
- **Gold is 3.3:1 on porcelain and 7.8:1 on lacquer.** So in light it may
  **never carry text** — hairlines, meter, brand mark only — and in dark it is
  the primary text accent. The same token, opposite rules per theme; a token
  name alone will not warn you.
- **Gold is not a Tailwind colour utility** (decided 2026-08-08). Deliberately
  absent from the `@theme` bridge's `--color-*` namespace, so `text-gold` does
  not exist; the hairline, meter and brand mark reach for `var(--dh-gold)`
  directly. This is the one case a token name provably cannot warn you about.
- **Tokens are named for their role, never their colour.** The first version
  used `--dh-cheong`/`--dh-hwang`/`--dh-hong` and one palette change made every
  name a lie. Now `--dh-primary` / `--dh-attention` / `--dh-danger`.
- **Nothing hardcodes a colour, size, radius, or duration.** Everything reads a
  token.
- **Every digit that can change in place is tabular** — headcount, meal counts,
  축의금 later. This is 정직함·믿음직함 in typography: a number whose width
  shifts as it counts reads as unstable. The mechanism is Tailwind's
  **`tabular-nums`**, not `.dh-num` (decided 2026-08-08 — the same mandatory
  rule must not be expressed two ways).
- **불참 is neutral, never red; 참석 is 초록** (초록원삼, the robe of a 반가
  bride). A guest who cannot come is a fact, not an error. Red belongs to
  destroying data only — and destructive actions always carry a verb and are
  outlined not filled, because 자적 and 대홍 are both reds.
- **Ledger rows are flush, hairline-separated — never cards.** Per-row cards
  cost ~8px of vertical rhythm each and break scanning at 400 rows. Radius is
  for things genuinely detached: chips, buttons, sheets.
- **Body text never goes below 15px.** Hangul packs more strokes into the em
  than Latin. 13px is for metadata fragments, never sentences. Korean running
  text gets 1.65 leading, not the Latin-typical 1.5. **No italics** — Korean
  has no italic tradition and synthesised obliques look broken.
- **Two faces: Pretendard for UI, RIDIBatang for display.** RIDIBatang is used
  in exactly three places — the headcount, screen titles, the brand mark — and
  **never the list**: Korean serif at 15px across 400 rows is slower to scan.
  Both are in `design/fonts/` with licences and measurements; regenerate the
  preview subsets with `design/fonts/subset.sh` when preview text changes.
- **Typeface candidates are decided by measuring the font, not by taste.**
  Arita Buri and Noto Serif KR were rejected for having no `tnum` (so they
  cannot carry the headcount); Song Myung and Hahmlet for omitting most of the
  11,172 Hangul syllables, and this product's content is people's names. Read
  `GSUB`/`hmtx` before recommending a face.
- Ten components plus four foundation cards cover v1 — inventory in the note.

## Screen rules

The product facts these implement are in the root `AGENTS.md`. What follows is
what `web/` is required to do with them.

- **Ledger + headcount on one screen is the first fixed point of the screen
  design** — splitting them turns one action into tap → navigate → check →
  return, which is exactly what a spreadsheet with a SUM already does.
- **Search is on the ledger, as a Field variant**; the filter chips (side +
  attendance) are a **Tag with a selected state**. Filters narrow a list; they
  do not find a person. The real trigger is "김영수 못 온대" and that needs two
  syllables, not a 400-row scroll — it is the second most-used control after
  the attendance chip.
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
