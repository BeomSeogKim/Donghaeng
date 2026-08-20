# Decision — the ledger read: no page, entry order, and what 참석 means to a filter (2026-08-20)

`#147`, the backend half of `#15`. `GET /weddings/{weddingId}/guests` is the read
the whole frontend stands on — 원장 is home, and `#135` `#12` `#13` `#14` are all
sheets that open over it — so three things it had to settle are seam commitments
rather than implementation details, and none of them was written down anywhere.

## 1. It does not paginate, and the answer had to be stated

The issue named this as the thing that could not be left silent: the frontend
either builds infinite scroll or it does not, and it cannot start until it knows.

**It returns the wedding's whole live ledger in one response.** Four reasons, in
the order they carry weight:

- **The collection is bounded by the wedding rather than by the product.** 200–800
  rows, every one of them entered by the couple. There is no path where this
  becomes a feed, which is the only thing paging exists for.
- **The screen needs all of it anyway.** 이름 검색 (`#16`) is the second most-used
  control in the product (`2026-08-07-design-screens-and-flow.md`), and a search
  that can only see the page in hand is wrong. Paging would force search onto the
  server before anyone asked for it there.
- **Paging fights the one interaction the ledger has.** Tap 참석 → the row mutates →
  the client refetches. Merging that into cached pages while responses can land out
  of order is a class of bug this product cannot afford: 숫자가 뒤로 가는 것은 허용
  되지 않는다.
- **It keeps ordering cheap.** The client holding every row can sort by 이름 with
  `Intl.Collator('ko')` and the server never commits to a database collation — the
  one place where a laptop's Postgres and a managed one legitimately disagree.

**Restated as a trigger rather than a promise**: this holds while the ledger is one
wedding's guest list. It is worth reopening if a ledger of several thousand rows
becomes real, or if the response starts carrying per-guest meal counts and 축의금 and
the row grows several times over. Either way it would be a new response shape and a
spec change, announced — never a parameter that quietly appears.

## 2. Entry order, and it is contract

Oldest first. A database-chosen order reshuffles between two reads of the same
ledger and the couple taps by position; entry order also preserves an imported
file's own order, which is the order the parents wrote it in.

**It is deliberately not a claim that entry order is the right reading order.**
Sorting the ledger is a product question nobody has answered, and because §1 hands
the client every row, answering it later costs a client-side sort rather than an
API change. That is the second dividend of not paging, and it is why this was not
worth sending back to the founder as a blocking question.

## 3. 참석 상태 filters on what the headcount counts

`attendance=ATTENDING | NOT_ATTENDING`, selecting on
`coalesce(confirmed_attending, expected_attending)` — the same fallback the 식대
인원 sums (`2026-08-05-design-meal-headcount.md` §1).

**Because 원장과 인원수는 한 화면이다, the filter and the number may not disagree.**
An expected-only predicate would have shown a guest under the 참석 chip while the
number beside it counted them 불참, and that is the failure this product's first
value forbids. Nothing writes a confirmed value until `#13`, so the predicate is
written today against a column that is still always NULL — deliberately, since the
alternative is a filter whose meaning changes silently the day `#13` lands. The
test that holds it writes `confirmed_attending` through JDBC, because no endpoint
can.

**There is no `UNKNOWN` value and there may not be one on this parameter.**
`expected_attending` is NOT NULL, so the filtered value is never unknown. 아직
모르는 N명 is a *second, overlapping* axis — a 참석 guest can also be 미확인 — so
it cannot be a third value of an axis whose values must partition the list. If the
product wants that chip it is its own parameter. `?attendance=UNKNOWN` is a 400
today, asserted, because it is the value a client is likeliest to try.

**그룹 stays out entirely**, which was already decided (`#15`,
`2026-08-06-design-ledger-and-import.md` §1) and is recorded here only because the
endpoint is where someone would add it "for completeness". An unknown query
parameter is ignored, so a `groupCategory=…` sent by a hopeful client returns the
whole ledger rather than a wrong subset — asserted, since that failure is invisible
in the response.

## 4. A filter arrives as the set of values it accepts, never as `:param is null`

Worth recording because the obvious spelling fails, and fails in production shape
rather than in a compiler.

`and (:side is null or g.side = :side)` is the standard optional-filter idiom and it
throws `could not determine data type of parameter` against Postgres: `side` is a
`wedding_side` enum column, so the untyped NULL bind has nothing to infer from. It
is a masked 500 on the ledger's own screen, and — the part that makes it worth a
paragraph — **only on the request with no filter at all**, which is the one a hurried
test is least likely to make through every path.

So an absent filter passes every value (`side in :sides` with both) rather than a
null. Neither set may be empty, since `in ()` is not SQL; the service is where that
is guaranteed, and it is the only reason the two live in the service rather than in
the query.

## What this does not decide

- **How the ledger is sorted on screen.** §2 makes it the client's, and it stays a
  design question.
- **Where the 미확인 count is shown, and whether it becomes a filter.** `#17` owns
  the number; the parameter shape above is what a later chip would have to use.
- **이름 검색** (`#16`). Not this endpoint's, and §1 is what lets it start
  client-side.

Refs `#147`, `#15`, `#16`, `#17`, `#13`,
`2026-08-07-design-screens-and-flow.md`,
`2026-08-06-design-ledger-and-import.md`,
`2026-08-05-design-meal-headcount.md`,
`2026-08-20-decision-mutation-response-envelope.md`,
`2026-08-10-decision-soft-delete.md`
