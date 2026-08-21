# Decision — the entitlement belongs to the Wedding, and a subscription row is a term (2026-08-22)

The founder, having decided that 유료 멤버십 is a product requirement:

> 부부중 한명이 멤버쉽 가입을 하면 **그 웨딩은 사용할 수 있어야 한다**.

This records why that is right, the shape it produces, and — deliberately — how
little of it is being built now. **Payment is not in v1**; the entitlement's
*place in the model* is, because the place is what a later reader gets wrong.

**Nothing about billing existed in this repo before this record.** No table, no
column, no note, no issue. That absence is the reason to write this down rather
than to leave it to the change that finally needs it.

## 1. The rule is a consequence of one already written

Root `AGENTS.md`: **The Wedding, not the user, is the top-level unit.**

Hang the entitlement on a person and **"one seat can use this ledger and the
other cannot" becomes representable.** The ledger is one thing per wedding, so a
product where two members of it hold different powers over it is not a pricing
model — it is a bug that pricing would introduce. The rule is therefore not a
generosity ("we let the partner in for free"); it is what the top-level unit
already meant.

**Payment is a person's act; entitlement is the wedding's state.** Those are two
facts and they attach to two different things. `payer_id` records the first,
`wedding_id` carries the second.

## 2. `membership` was a trap, and removing the name removes it

This was the sharpest reason to act now rather than when payment is built.

Whoever adds paid membership later opens `membership`, sees a table with the
right name, and puts the plan column there. That table is keyed
`(wedding_id, user_id)` — **anything attached to it is per-person by
construction.** The mistake would be invisible in review, because the code would
read exactly like what was asked for.

[2026-08-22-decision-the-couples-two-seats.md](2026-08-22-decision-the-couples-two-seats.md)
drops `membership` for its own reasons. That it also removes this trap is a
second, independent argument for the same change: **nobody attaches a price plan
to a table called `wedding_party`.**

## 3. A row is a term, not a status — and the founder's scenario is why

The first shape drafted was one row per wedding with a mutable `paid_by`. The
founder tested it with a case:

> 신랑이 결제를 하고 있었어. 그러다 신랑이 끊고 신부가 결제를 해. 이 경우에도
> 커버 가능한가?

**Half.** The entitlement survives — it hangs on the wedding, so the ledger never
closes. But `paid_by` is overwritten and **"who paid for July" stops being
answerable.** For a refund, a dispute, or a question from either of them, the
answer is gone. This is money, and 정직함 · 믿음직함 is the first product value.

So a row is a **term**: `payer_id`, `started_at`, `ended_at`. A payer change ends
the live term and opens the next one. Past terms are never removed.

`ended_at` rather than `deleted_at`, and the distinction is one this schema has
already drawn: `guest_import.superseded_at` is described in `V1` as *not* a soft
delete and *not* a revision, but a later fact appended to a row whose own values
stay true. A term that ended is exactly that. **`wedding_subscription` has no
`deleted_at`** — a term is never removed; it ends.

**`current_period_end` is a third date and not a synonym for either.** It is what
the money already paid covers: paid through 8/31 and cancelled on 8/15 leaves the
wedding entitled until 8/31.

**`payer_id` references `app_user`, not `wedding_party`.** The payer may later
release their seat, and "they paid for that stretch" has to survive it. Pointing
at the seat would make the payment record follow the seat's fate, which is a
different fact's fate.

## 4. 웨딩당 활성 구독 1건, and it lives in an index

The founder's restriction:

> Wedding의 구독제 결제는 1건만 가능하다. 이미 활성 구독건이 있으면 추가 갱신 불가.

```sql
create unique index ux_subscription_live on wedding_subscription (wedding_id)
    where ended_at is null;
```

**Not a service check.** A second live term is not representable, so two tabs, a
double-tapped button, and two racing renewals all end in one term and one refusal
however the requests interleave. Same mechanism and same reasoning as
`ux_party_user` — [2026-08-21-decision-one-wedding-per-person.md](2026-08-21-decision-one-wedding-per-person.md)
§2 argued it once and it is not re-argued here. Ended terms remain and do not
occupy the slot, which is what a plain `UNIQUE (wedding_id)` could not have done.

**And the half that record also insists on: the index's refusal must be turned
into an answer.** A raw constraint violation is a 500, and a 500 tells the person
nothing. The loser of the race gets a 409 and the fact about their own wedding —
there is already one — with no retry in the recovery.

**The trap this creates, written down because it will be met on day one.** A
wedding is created with a **live `FREE` term**, so the first payment is a
*handover*, not an insert: end the free term and open the paid one in one
transaction. An implementation that just inserts violates this index on the very
first real payment. That is the test to write before the code.

`ck_subscription_term_order` (`ended_at is null or ended_at >= started_at`) is in
the schema and not in a validator for the reason
`ck_wedding_guaranteed_headcount` is: a term ending before it starts is
meaningless under every reading, and unlike the varchar value sets it will never
need an `ALTER`.

## 5. History is two layers; one is built and one is not

The founder: **membership 결제 히스토리는 남는게 좋을 것 같아.** Two things can
mean, and only one is being built.

| Layer | Answers | Now? |
|---|---|---|
| **Terms** — `payer_id · started_at · ended_at · plan` | who held the entitlement, and when | **yes** |
| **Payments** — amount, approval id, PG response, refunds | each individual charge | no |

A single term contains many charges — twelve monthly renewals inside one payer's
stretch — so payments are a **child of a term**, not the term itself. Their
columns are named by whichever PSP is chosen, and **no PSP has been chosen**, so
naming them today would be a guess wearing a schema's clothes.

## 6. What is deliberately not built

- **PSP reference columns.** See above.
- **A gate that actually refuses anything.** What free grants is undecided (§7),
  and a gate cannot be written before the thing it enforces.
- **Any UI for paying.** There is nothing to pay.

What *is* built: the table, one live term per wedding created with the wedding at
`plan = 'FREE'`, the handover operation, and one place that reads the entitlement
beside the wedding-scope resolution.

## 7. Open questions, both parked on purpose

**The free/paid boundary.** How many guests are free, whether import is paid,
whether the headcount is paid — none of it is decided anywhere. Deciding it now
means guessing before a single couple has filled a ledger; deciding it after
launch means reading real use. `plan` is therefore a column with one value in it,
and **the point of this work is that the column exists**, not that it varies.

**How a gap or an overlap between terms resolves.** 신랑 paid through 8/31 and
cancelled on 8/15; 신부 starts on 8/20. The wedding is plainly entitled
throughout — the money covers it — but *when the first term ends* depends on
cancellation timing, proration and refund rules that only exist once payment
does. The model can represent the handover; the policy is not written yet.

**Why payment is not in v1.** Launch is 2026-08-31 and fixed
([2026-08-19-decision-launch-date-and-google-only.md](2026-08-19-decision-launch-date-and-google-only.md)).
A Korean recurring-payment integration waits on a PSP merchant review — heavier
and longer than the Kakao and Naver OAuth reviews that already pushed `#89` to
`post-v1`, and just as far outside our hands. And nobody pays on launch day for a
ledger they have not yet filled.
