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

**Both redirects exist, and each is the other's mirror.** 원장 sends a person with
an empty list to 웨딩 만들기; **웨딩 만들기 sends a person with a non-empty list
to 원장.** The second one was written into this record before it was written into
the code, and the round of review on `#148` is what found the gap — corrected
2026-08-21, and stated here as a pair because either one alone is a loop or a
hole.

**There is no way to create a second wedding from the app** — founder's call, and
the guard above is what enforces it. The API allows one and a person may belong to
several; v1 has no wedding switcher, no delete, and no route that carries a
`weddingId`, so a second wedding would take `[0]` and leave the first ledger with
nothing pointing at it. `create.isPending` does not cover this: it is one
component's state, and the ways in are a bookmark, a typed URL, and a second tab
still parked on the form after the first one submitted.

**A failed `GET /weddings` on that screen shows neither the form nor a redirect.**
Not knowing whether they already have a wedding is exactly the case where offering
the form is expensive, and the cost is asymmetric — a retry costs a tap, a
wrongly-offered form costs the ledger.

**로그아웃 lives on both screens**, which is also a consequence of the home screen
going. A person whose list is empty is sent to 웨딩 만들기 and cannot leave it, and
an empty list is not only 최초 1회: **it is also what a removed membership looks
like** (docs/api-spec.md § GET /weddings), so that screen can be where someone
lives rather than a screen they pass through. The button carries its own failure
message on both, because `POST /auth/logout` always answers 204 — a non-204 means
the request never arrived and the session is still live, and a button that
un-disables itself in silence says the opposite.

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

**That nesting is asserted, not described.** `#135` has not been written and
`ledgerQueryKey` therefore has no production caller yet, which would have left the
load-bearing half of this section as a comment for as long as it took someone to
write the first mutation. `LedgerPage.test.tsx` invalidates the middle key with a
filter pressed and asserts that the filtered request goes out again and that the
unfiltered entry behind it is marked stale — so `#135` inherits a tested contract
rather than a claim.

## `keepPreviousData` may hold rows, but not speak for them

The list keeps the previous filter's rows while the next request is in flight —
the list is not the headcount, and blanking it on every chip is the screen
changing its mind in front of the couple.

**The two empty notices do not get the same licence, and gating them was a
correction** (`#148` review, 2026-08-21). Both name the filters that are pressed
*now*, while the rows they are speaking about belong to the filters that were
pressed a moment ago: press 신랑 with no matches and then 신부 with twelve, and the
screen announced 신부측에 아무도 없다 and then contradicted itself. The same shape
on the other branch is worse — a 400-row ledger told it is empty. So both are
gated on `isPlaceholderData`, and while the query is holding someone else's rows
the screen says only that it is loading.

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

## Two readings that went to review, and how they came back

- **The filter chip is 2rem tall, not the 44px tap floor — upheld.**
  `12-tag.html` sets `.filter { min-height: 2rem }` and a component's visual rules
  come from the part; `--dh-tap-min`'s "floor for anything tappable" is argued in
  the flow record about the *attendance* chip, and nothing states a blanket floor.
  A 44px toolbar pushes the list down on the device the ledger is mostly read on.
  (The chip's *rest border* was wrong and was corrected in the same round: the
  part gives `.filter` a transparent border and brings the hairline in on hover.)
- **A 401 on the ledger read showed the generic failure — that was wrong.** It
  blamed the connection for a session state, on the screen the couple live on, and
  the state had no exit: 다시 시도 would 401 forever, `refetchOnWindowFocus` needs
  a focus event that someone sitting in the tab never produces, and that branch
  renders outside the frame so there was no 로그아웃 either. **The answer moved to
  the client** — a `QueryCache`/`MutationCache` `onError` that writes the session
  to `null` on any `ApiError` 401 — and `useCreateWedding`'s own 401 handler was
  deleted with it. One status, one answer, in one place
  (`2026-08-21-decision-query-defaults-and-mutation-ordering.md`).
