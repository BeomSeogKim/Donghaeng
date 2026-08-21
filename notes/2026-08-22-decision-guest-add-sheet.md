# Decision — 하객 추가는 시트이고, 한 명 넣었다고 닫히지 않는다 (2026-08-22)

`#135`, the frontend half of `#11`. The backend half is merged and settled the
request (`2026-08-20-decision-guest-entry-side-and-companions.md`,
`docs/api-spec.md § POST /weddings/{weddingId}/guests`); this record settles what
the screen does with it, and only the parts a later reader would otherwise
re-litigate.

**What this endpoint is, restated because it changes how the screen is read**:
after the 2026-08-21 recut, direct entry is the **only** intake path in v1 — the
vendor-email parser and the CSV import went to `post-v1`. Every row of every v1
ledger is typed on this sheet. It is not a fallback for the import; it is the
product's writing surface.

## A sheet over 원장, not a route

원장 is home and everything else opens over it or leaves and comes back
(`2026-08-07-design-screens-and-flow.md`), and the flow's own table calls this
one 하객 추가 **시트**. So it is `useState` on the ledger — `App.tsx` and
`lib/routes.ts` are untouched, and no `weddingId` reaches a URL.

**No history integration, deliberately.** A route would make Back mean "close the
sheet" on one screen and "leave 원장" on the next, and it would put a navigation
between the couple and the list they are filling. What the sheet does carry is
Escape and a 닫기 button.

**The issue said "필수는 이름 하나"; the spec says name AND 측, and the spec wins.**
That sentence predates
`2026-08-20-decision-guest-entry-side-and-companions.md` §1: `wedding_side` has
two values and no residual, so any default is a claim the couple never made — on
one of the ledger's two filters and one of its aggregation axes. The sheet
therefore opens with **neither side chosen**, which is the honest state of a
question nobody has answered, not an unfinished form.

## It stays open after a guest is added — the decision in this change

Closing the sheet per guest is one re-open tap per row. A couple works down a
list their parents sent them over KakaoTalk, so a 200-row ledger is 200 extra
taps on the product whose entire claim is that it is less work than a
spreadsheet with a SUM.

So: the form resets, focus returns to 이름, and **the 측 is carried over.** That
is not a silent default — it is a filled, visible control still showing the
answer the couple gave a moment ago, and the spec blesses a pre-selection as a
frontend affordance while refusing to make it the endpoint's promise. A run of
guests is normally on one side; the couple flips it when the run changes.

**And the number is said inside the sheet**: `김영수님을 추가했습니다 · 식대
인원 128명`, in a `role="status"`. On a phone the sheet covers the pinned
인원수, so without this the one thing the product exists to show — the number
moving — happens behind the couple's own hands. It is the number **this create's
own response carried**, not a second read: the same value written into the query
cache in the same `onSuccess`, so there is one number in two places and never two
numbers.

**That last sentence is only true because `setHeadcount` cancels first** — found
in review, 2026-08-22, and it was not true as first written. 인원수 runs
`staleTime: 0` with `refetchOnWindowFocus`, both deliberate, so the ordinary
sequence is: the couple tabs back from KakaoTalk, a read starts that will answer
128, they tap 추가, the write answers 129, and then the older read lands.
query-core resolves a fetch by calling `setData` unconditionally — no comparison
of when the two numbers were computed — and `setQueryData` cancels nothing on its
own, so the pinned figure would have dropped back to 128 beside a sheet saying
129. **A number lagging a tap is fine; a number moving backwards is the one
thing this product may not do.**

The guard is inside `setHeadcount`, by that function's own principle that a
caller must not be able to write this number by hand. Its two lines are ordered,
and not for the obvious reason: `cancelQueries` defaults to `revert: true`, which
restores the state captured when the cancelled fetch *started* — the stale
number — and applies it **synchronously**, inside the call. So the revert lands
first and the write overwrites it. Await the cancel and write in the `then`, or
put the two lines the other way round, and the stale value is what survives.

## What the client sends, and what it refuses to send

- **Every member is sent, including the ones sitting at their default.** An
  omitted member and an explicit `null` are the same thing to the server, so
  nothing is assembled conditionally, and what is on the screen is what is in the
  row. `groupCategory` is the one member that may not be `null` — the generated
  type gives an enum no null branch — so it is always sent as a value.
- **Trimmed before it is sent**, because every length bound is measured on what
  you send, before the server's own trim. A blank optional field is sent as
  `null` rather than `""`.
- **The party-size stepper floors at 1** and disables the button below it. A
  party of zero is a 400 and 불참 is how the couple says nobody is coming, so
  the refused request is unreachable rather than merely avoided — the same shape
  as the filter chips holding one value per axis.
- **The free label is collected and never offered as a filter or a grouping.**
  Free labels fracture on typing variants and a fractured group is a wrong
  number.

## The number and the list come from two different mechanisms, on purpose

The two are written by different callbacks, and that asymmetry is the ordering
record's (`2026-08-21-decision-query-defaults-and-mutation-ordering.md` § The
backstop), not a choice made here — that record names `#135` as the hook that
writes the first `onSettled` invalidation, and this change is where it lands.

- **The headcount is written from the response, in `onSuccess`** (`setHeadcount`).
  Fetching it beside the mutation lands outside the window mutations are
  serialised in and puts the out-of-order race straight back.
- **The ledger is invalidated in `onSettled`, not written into** —
  `ledgerQueryKey`, the whole ledger rather than the filter combination on
  screen. Which filter combinations a new row belongs in is the *server's*
  answer, and reproducing it in the client would be a second implementation of
  원장's filtering: the same class of mistake as computing the headcount here.
  The invalidation is **not awaited**, because query-core waits on a returned
  promise before releasing the next mutation.

**`onSettled` rather than `onSuccess` is what makes the sheet safe to leave
open** — corrected in review, 2026-08-22, after it was written on `onSuccess`. A
POST can commit on the server and lose its response to a dropped connection or a
timeout; mutations are `retry: 0`, so there is no second attempt and `onSuccess`
never runs. The couple sees the failure copy with their text still in the fields
and presses 추가 again — which is the behaviour the section above deliberately
invites. On `onSuccess` the ledger behind the sheet would still be one guest
short while the row existed, so the second press writes 김영수 twice and the
number is honestly computed from a wrong ledger. On `onSettled` the list
refetches on the failure too, and the row is there to be seen.

The contract was asserted in `LedgerPage.test.tsx` before this screen existed,
by a hand-written mutation standing in for it. **That scaffold is deleted in this
change** — 하객 추가 makes the same assertions through the button the couple
actually presses, and two components named 하객 추가 on one ledger is one too
many.

## Smaller calls, stated so they are not re-opened

- **The button is in the pinned header, beside 로그아웃** — not in the tools row,
  whose chips are 2rem tall precisely so the pinned block does not push the list
  down, and not at the bottom, which belongs to the list.
- **The empty ledger points at that button rather than repeating it.** Two places
  to press for one action is worse than a sentence.
- **All eight writable members are on one scroll, with no "더 보기" disclosure.**
  하객 수정 (`#12`) does not exist, so a member absent from this sheet cannot be
  set at all in v1.
- **`--dh-scrim` is a new token, in both themes.** The dim behind a sheet is a
  colour and nothing hardcodes a colour; it could not be an alpha on
  `--dh-ink`, because ink inverts between the themes and a scrim does not.
- **The segmented control became `components/Choice.tsx`**, and the pressed fill
  is **per option**: 측 fills 자적, 참석 fills 초록, and **불참 fills the
  neutral, never red.** 웨딩 만들기's own `SideChoice` is **folded into it and
  deleted** — 95 lines, its suite green throughout. It was going to be left as a
  follow-up, and review corrected that: this change is what created the second
  component painting 측, so removing it is cleaning up after this diff rather
  than refactoring next to it.
- **A `<div role="dialog">`, not a native `<dialog>`.** jsdom 30 does not
  implement `showModal()` — verified, not assumed — so building on it would have
  meant a screen no test could open.

## What was deliberately not built

- **A toast.** Closing back onto a moved number and a new row is the
  confirmation; a floating one on top of it would be decoration.
- **A focus trap.** `aria-modal` states the modality and Escape and 닫기 both
  close the sheet; a hand-rolled trap is code with no test behind it in a jsdom
  that has no `inert` either. It arrives with 하객 상세 (`#12`), which is the
  second sheet and therefore the moment the chrome earns its own component.

## Open, and not to be pre-empted

**Whether the 측 should carry over at all** is with the founder as of 2026-08-22.
The review's argument: this sheet refuses a default for the first guest because
"a default would be a claim the couple never made", and then pre-selects one for
every guest after — and a guest filed on the wrong 측 cannot be fixed in v1,
because `#12` does not exist and there is no delete. The behaviour above stands
until that is answered, and the code is not being reshaped to make either answer
cheaper.
- **Per-meal-type counts.** They hang off meal types only the couple can create
  (`#10`), so a guest is added first and their meals set after (`#14`).
