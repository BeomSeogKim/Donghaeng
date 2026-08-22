# Decision — 하객 추가 시트는 측과 그룹을 둘 다 이월한다 (2026-08-22)

Closes `#194`. Founder's call, and it settles the question
`2026-08-22-decision-guest-add-sheet.md` left open in its last section.

**The call: the sheet carries both 측 and 그룹 over to the next guest. And
`#12` (하객 상세 — 수정·삭제) is confirmed in `v1`, which is the premise this
answer rests on.**

## What was actually wrong

The sheet stayed open and reset the form, carrying **측** and resetting
**그룹** to `OTHER`. Neither half was argued for on its own; the asymmetry was
an accident of which member had a safe zero value.

Three things were wrong with it, and the third is the one that decides.

1. **The sheet broke its own principle at guest two.** It opens with no 측
   chosen because "a default is a claim the couple never made"
   (`2026-08-20-decision-guest-entry-side-and-companions.md` §1). From the
   second guest on it made that claim for them.
2. **It carried the wrong one.** Parents' lists arrive on the **그룹** axis —
   "신랑 친구 20명", "직장 15명". The member that would have saved twenty taps
   was the one being reset, and the member that can be silently wrong was the
   one being kept.
3. **A wrong 측 was unfixable.** With no 하객 수정 and no delete, a row filed on
   the wrong side sat permanently on one of the ledger's two filters and one of
   its aggregation axes.

## Why carry both rather than reset both

Both are defensible; the asymmetry was not. The tie is broken by whether a
mistake can be undone, which is why `#12` had to be decided in the same breath.

Parents' lists come in **blocks**, and inside a block neither 측 nor 그룹
changes. Carrying both matches the shape of the work; resetting both costs
roughly two taps per row on the product whose whole claim is that it is less
work than a spreadsheet with a SUM. Direct entry is the **only** intake path in
v1, so that arithmetic is not a corner case — it is every row of every ledger.

**With `#12` in v1 the carried-over mistake is recoverable**, and the argument
that made carrying 측 dangerous is spent. Without it the answer inverts.

## The premise is load-bearing — say so

If `#12` leaves `v1`, this decision returns to **둘 다 초기화**. That is not a
hedge; it is the whole reasoning above, which trades a recoverable error for
saved taps. A later reader dropping `#12` for schedule must reopen this.

## What does not change

The sheet still opens with **neither 측 nor 그룹 pre-answered for the first
guest** of a session. Carry-over is the couple's own previous answer still
showing; a fresh sheet has no previous answer to show, and inventing one there
is the claim nobody made.
