# Decision — the ledger screen: 원장 is home, and the order on it is the client's (2026-08-21)

`#148`, the frontend half of `#15`. The backend half is merged and settled the
request (`2026-08-20-decision-the-ledger-read-and-its-filters.md`); this record
settles what the screen does with it, and only the parts a later reader would
otherwise re-litigate.

## 원장 is home, at `/`

`/` renders the ledger for a signed-in person who has a wedding, and `ledgerPath`
stayed `/` rather than moving. There is essentially one screen
(`2026-08-07-design-screens-and-flow.md`); a ledger at its own path would need a
home screen in front of it whose only job is to link there.

Two consequences worth stating because they are deletions:

- **The old home screen is gone**, and with it the only place the signed-in
  person's name was rendered — including the `name ?? '이름 없음'` fallback for a
  provider that returns none, and its test. Nothing renders `Session['name']`
  today. It comes back with 설정 (`#8`), which is where "who is signed in on this
  device" belongs.
- **A person with no wedding is redirected to 웨딩 만들기**, replacing the link
  that used to sit on the home screen. `GET /weddings` returning `[]` is what
  makes 최초 1회 decidable, and it is not an error state.

**There is no way to create a second wedding from the app.** The API allows one
and a person may belong to several; no screen offers it, and the redirect above
makes the form reachable only when the list is empty. That is v1's answer, not an
oversight — v1 also has no way to switch between weddings.

## 이름 가나다순 is the client's order — founder's call

The API returns the wedding's whole live ledger in entry order and says so as
contract. **The screen sorts it by name** with `Intl.Collator('ko')`, applied in
the query's `select` so the cache still holds the order the server promised.

The reason it matters now rather than later: **이름 검색 (`#16`) was cut to
post-v1**, so in v1 the sort *is* how a couple finds a person in a 400-row list.

- **Not on the server.** It would commit the API to a collation whose behaviour
  differs between a laptop's Postgres and a managed one, and the client holds
  every row anyway.
- **No sort control.** One order, and it is this one. A control would be a second
  thing to decide on the screen whose whole claim is that it is calm.
- Ties keep entry order underneath, because `sort` is stable and 동명이인 is a
  real case the API accepts on purpose.

## The filter chips hold one value per axis, and that is what makes the 400 unreachable

`?side=GROOM&side=BRIDE` is a 400. The endpoint refuses a repeated parameter
deliberately: a repeat binds to its first value, so unrefused it would have
answered 신랑측 only — a 200, a plausible ledger, and no signal that half of it
is missing. "Both" is spelled by **omitting** the parameter.

So the client's filter state is **one optional value per axis**, not a set of
pressed chips:

- pressing an unpressed chip selects it; pressing the pressed one clears the
  axis; pressing the other one replaces it.
- "both" is the absence of a selection, which is also the resting state — so the
  toolbar is not four filled chips at rest, on the screen where filled colour is
  supposed to mean something.
- the request is built with `URLSearchParams.set`, which cannot emit a parameter
  twice even if asked.

**The alternative — two independently pressable chips per axis, both on meaning
"both" — was rejected.** It makes the refused request expressible (the mapping
then has to remember to collapse it), and it leaves "neither pressed" meaning
nothing the API can say.

**There is no 미확인 chip.** Attendance has two states and the second slot is
withdrawn (`2026-08-21-decision-attendance-is-two-states.md`);
`?attendance=UNKNOWN` is a 400 today.

## The wedding id comes from `GET /weddings[0]`, and is not in the URL

The ledger has no route parameter. It reloads by asking `GET /weddings` and
taking the first entry, which is contract (newest first), and it renders its
header — the couple's names — from that same entry. **`GET /weddings/{weddingId}`
is therefore not called on this screen**: it would be a second round trip for
four fields already in hand. It starts to matter the day a wedding id can arrive
from outside the list — a shared link, or a wedding switcher — and v1 has neither.

`POST /weddings` now writes its response into the weddings list cache. Without it
the couple who just submitted the form would be bounced straight back to it by a
list fetched one request earlier that still says they have none.

## Query keys, which `#17` and `#135` build on

    ['weddings']                              the couple's weddings
    ['weddings', weddingId, 'guests']         the ledger, whatever it is filtered by
    ['weddings', weddingId, 'guests', filters] one filter combination — what is fetched

A guest mutation invalidates the **middle** key, so a guest added while the
신랑측 chip is pressed does not leave the unfiltered list stale behind it. The
nesting under `['weddings']` is deliberate: signing out removes every key whose
first element is not `session`, and a ledger belongs to a wedding.

## What was deliberately not built

- **The headcount slot is empty.** `#17` is not built and its number does not
  exist, so the screen leaves the position — above the tools, above the list —
  and puts nothing in it. No placeholder number, no skeleton, no type. A
  placeholder here is a wrong number, and a skeleton promises a number that no
  endpoint answers.
- **The PC table is deferred, and the row is one responsive component.** 60px on
  mobile and 44px on desktop are both honoured — the name and its metadata stack
  on a phone and sit on one line on a laptop, which is what the two heights mean.
  What earns a *second structure* is the aggregation rail and the contact column,
  because without them the desktop ledger is a wide phone screen (`web/AGENTS.md`)
  — and neither exists (`#17`, `#18`). The split happens when they land.
- **The attendance chip is a readout, not a control.** Tapping it is `#13`; a
  button here would be a control that does nothing.
- **The empty ledger offers no action.** 하객 추가 (`#135`), the import and the
  vendor-email paste are what fill a ledger and none exists yet, so the screen
  says the ledger is empty and that the add screen is on its way, rather than
  showing a button that does nothing. It is replaced by `#135`.

## Two readings a reviewer may want to check

- **The filter chip is 2rem tall, not the 44px tap floor.** `12-tag.html` sets
  `.filter { min-height: 2rem }` and a component's visual rules come from the
  part; `--dh-tap-min`'s "floor for anything tappable" is argued in the flow
  record about the *attendance* chip, and a 44px toolbar pushes the list down on
  the device the ledger is mostly read on. If that reading is wrong the fix is one
  class.
- **A 401 on the ledger read shows the generic failure**, not the login screen.
  The read path has no `onError`, and writing the session to `null` from inside a
  `queryFn` was not worth it for a state that resolves on the next window focus
  (the session refetches) or on a reload. It is worth revisiting when `#135` adds
  the first mutation on this screen, which does have an `onError`.
