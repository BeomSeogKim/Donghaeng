# Decision — what the number says in each state, and where it is written from (2026-08-21)

`#161`, the frontend half of `#17`. The backend half is merged and settled what the
number *is* (`2026-08-21-decision-the-headcount-endpoint.md`); this settles what the
screen does with it. `#148` left the slot on 원장 empty on purpose — "no placeholder
number, no skeleton, no type" — and this is the change that fills it, so only the
parts a later reader would otherwise re-litigate are written down.

## 1. An uncounted number is never drawn as 0, and the two are told apart by a dash

There are three ways the figure can have no number, and only one of them is a
number:

| state | what the screen shows |
|---|---|
| the read is in flight | `— 명`, and the block carries `aria-busy` |
| the read failed | `— 명`, plus 인원수를 불러오지 못했습니다 and 다시 시도 |
| the ledger is empty | **`0 명`** — a counted zero, and a 200 (docs/api-spec.md) |

`0` is a claim: it says the couple's ledger sums to nobody. Drawing it while the
answer is still in flight is the same shape as the bug `#148` corrected on the empty
notices — a screen asserting something it has not been told — except that here the
thing asserted is the number itself, on the product whose first value is that the
number is never wrong. The em dash holds the figure's position so nothing jumps when
the number lands, and it cannot be mistaken for a count.

**A failed refetch drops the number it was holding**, which is the subtle half.
React Query keeps the last successful data through a failed refetch, and a refetch
here is ordinary rather than exotic: `staleTime` is 0 and the window regains focus.
Left alone, the screen would show a 40px figure from some earlier moment at full
confidence with a 13px note beside it — a number labelled possibly-old is a number
the couple cannot take to their venue, and the label is the part nobody reads. The
list beside it already behaves this way: its failure replaces the rows it was
holding rather than sitting under them.

## 2. Two reads, two answers — neither failure decides the other

원장 opens `GET .../guests` and `GET .../headcount` in parallel and neither waits on
the other, which the spec asks for in as many words. The consequence worth stating is
what happens when only one of them fails:

- **A failed headcount does not blank the ledger.** The couple can still work the
  list, and the number says it is missing, in its own place, with its own 다시 시도.
- **A failed ledger does not blank the number.** The figure is the server's own
  aggregate and is not derived from the rows on screen; hiding it because a second
  request failed would be inventing a dependency the endpoints do not have.

A 401 reaches neither branch — the client answers every 401 with the login screen
(`2026-08-21-decision-query-defaults-and-mutation-ordering.md`).

## 3. With no 보증인원 the screen says nothing about 보증인원

`guaranteedHeadcount` is **absent, not null**, until the couple agrees a number with
their venue, and `#8` — the screen that will set it — is not built, so in v1 today it
is *always* absent.

**The screen renders the 식대 인원 alone: no comparison, no meter, and no mention of
보증인원 at all.** Not a dash, not an empty slot, not a "보증인원을 설정해 주세요".

The alternative — naming the missing number so the couple knows the app understands
the concept — was rejected because there is nowhere to go: a prompt for a value the
app has no screen to accept is a question the couple cannot answer, and it would sit
on the one screen they live on, permanently, for every couple. `#148` established
what an honest empty state says here ("하객을 추가하는 화면은 아직 준비 중입니다")
and that shape does not transfer: an empty ledger is a state the couple can *leave*
once `#135` lands, whereas a missing 보증인원 is not the couple's omission at all —
most of them have not booked a venue yet.

**When it is present, the comparison is the screen's own subtraction** — 보증 N and
여유 N, or 초과 N in 치자 when the ledger is over. Never red: being over the number
the venue wrote down is something to see, not destroyed data. The server sends two
numbers and never a difference, a percentage or a recommendation, and never will.

## 4. Where the number comes from after a write

Every wedding-scoped mutation returns `{resource, headcount}`, recomputed inside the
same transaction as the write. **`setHeadcount(queryClient, weddingId, headcount)` in
the mutation's own `onSuccess` is the only way that number enters the cache**, and
`#135`, `#12` and `#13` all call exactly that. It is a function rather than a
documented `setQueryData` so the key and the shape are bound together and checked at
compile time.

It is asserted rather than described: a test fires a real `POST .../guests` through
MSW, writes the response's `headcount` from `onSuccess`, and asserts the figure moves
in place **while the headcount endpoint is requested exactly once** — the same reason
`#148` asserted the ledger key's invalidation before `#135` existed.

Two things this deliberately does **not** add, both from the ordering record:

- **No second request beside the mutation.** A `GET .../headcount` fired alongside a
  mutation lands outside the window mutations are serialised in and puts the
  out-of-order race straight back.
- **No new ordering mechanism.** Serialisation already exists on the client and is
  tested; this screen needed nothing from it but the rule above.

**The headcount key is a sibling of the ledger key** — `['weddings', id, 'headcount']`
beside `['weddings', id, 'guests']` — so a guest mutation's `onSettled` invalidation
of the ledger does **not** refetch the number, and that is correct rather than an
oversight: the number arrived on the mutation's own response, and a refetch would be
a second answer to a question already answered. Cross-device drift is covered for both
keys by `refetchOnWindowFocus`. A mutation that wants both back from the server can
invalidate `['weddings', id]`, which is a prefix of both; nothing does today.

## 5. Where the screen departs from the Stat part, and why

`design/components/parts/15-stat.html` is still the source for the figure — the
display face, 40px in 자적, tabular, the 4px meter that settles over `--dh-dur-count`.
Three things in it are not on screen, and a later reader comparing the two will ask:

- **확정 · 미정 are gone.** Attendance has two states and the 미확인 count was
  withdrawn with the second slot (`2026-08-21-decision-attendance-is-two-states.md`).
  The part predates that call.
- **No card border.** The part draws a bordered block because a gallery card needs an
  edge; on 원장 the figure sits inside the pinned header, which already has one, and a
  bordered block inside a bordered bar is a box in a box.
- **The eyebrow is `--dh-ink-muted`, not the part's `--dh-ink-faint`.** ink-faint is
  3.2:1 — decorative and disabled only (`design/AGENTS.md`) — and this label is what
  tells the couple which number they are looking at, which makes it text.

## What reopens this

- **`#8` landing**, which gives 보증인원 a write path and makes §3's present-branch
  the ordinary case rather than the unreachable one.
- **`#10`/`#14`**, which add members to the response: 유아 인원 stands *beside* the
  식대 인원 and is never folded into it, and it must be reachable on mobile
  (`web/AGENTS.md`).
- **Real couples asking "이 숫자 중 몇 명이 확실한 거냐"**, which is the trigger on
  the two-state record and would bring a third number back to this block.

Refs `#161`, `#17`, `#8`, `#135`, `#13`, `#42`,
`2026-08-21-decision-the-headcount-endpoint.md`,
`2026-08-21-decision-attendance-is-two-states.md`,
`2026-08-21-decision-ledger-screen.md`,
`2026-08-21-decision-query-defaults-and-mutation-ordering.md`.
