# Decision — row concurrency, and why the audit trail is the answer (2026-08-20)

Closes `#102`. Written when `#17` and `#13` were about to be picked up, which is
the last moment this could be decided cheaply: both of them, plus the remaining
half of `#44`, stand on it.

## The question that was actually being asked

`#37` put `@DynamicUpdate` on `UserSession`. The open question was whether that
becomes the default for wedding-scoped entities, and whether it is enough.

It is not enough, and the shape of what it misses is the whole decision.
`@DynamicUpdate` rebuilds the UPDATE statement from the fields that actually
changed, so two operations touching *different columns* of one row stop
clobbering each other. Two operations touching the **same** column still end in
last-write-wins. And:

> **부부 둘이 각자 폰에서 같은 하객의 참석을 누르는 건 이 제품의 평범한 사용이지
> 엣지 케이스가 아니다.**

The attendance toggle is the same-column case. It cannot be made a
different-column case: giving 신랑 and 신부 separate attendance columns for one
guest would mean the product holds two answers to "이 하객은 오는가", and then
the ledger cannot say which one it shows. Attendance is one value per guest.

## The call

**1. `@DynamicUpdate` is the default for wedding-scoped entities.** The failure
it closes is silent, does not throw, and generalises to every row two operations
write through different paths — which in this domain is most of them. `guest`
alone takes attendance, meal counts and soft-delete down separate routes. The
cost is rebuilding one statement per flush, negligible at v1 volume.

**2. No `@Version` on `guest` in v1.** Optimistic locking detects the conflict
and then has nothing acceptable to do with it: the only user-facing move is
"다시 시도하세요" on a toggle, and this product does not get to say that on its
most-used control.

**3. Last-write-wins is correct for the state — because every v1 mutation
carries an absolute value, never a relative one.** The attendance payload is
`{"attendance": "NOT_ATTENDING"}` — *make it this*, not *flip it*. So when 신랑
taps 불참 and 신부 taps 참석, each of them read their own screen, each sent the
value they meant, and the one who tapped last got what they asked for. Nothing
the couple intended was lost.

**4. But the overwritten value does not vanish.** Every mutation writes
`guest_change` rows in the same transaction as the change itself — one row per
changed field, with old value, new value, who, when, and source. 신랑's 불참 is
still there with his name and his timestamp on it, which is exactly the property
the table was built for: *"이 숫자 누가 바꿨어?"*

Point 4 is the founder's, and it is what makes points 2 and 3 acceptable rather
than merely convenient. Silent overwriting and *recorded* overwriting look the
same in the database and are not the same product. 정직함 · 믿음직함 is the
first product value, and a ledger that quietly loses one partner's correction is
not honest about what it knows.

## The correction worth recording

The founder's first framing was to solve the same-column case at the transaction
level. **A transaction does not solve it**, and this is worth writing down
because it is the obvious place to reach and the reach fails quietly.

A transaction gives atomicity and isolation, not conflict *detection*. Two
transactions writing one column serialise — and then both commit successfully,
with the second overwriting the first. A row lock (`SELECT … FOR UPDATE`)
changes the order in which they happen and nothing about the outcome. There is
no isolation level that turns last-write-wins into a detected conflict; that is
what `@Version` is for, and `@Version` is what point 2 declines.

What actually makes the outcome correct is not the transaction. It is that the
payload is absolute (point 3). The transaction's job here is narrower and still
essential: **the audit row and the change it describes commit together or not at
all.** An audit log that can disagree with the row it audits is worse than none.

## The tripwire

Point 3 holds only while every mutation is absolute. **No v1 endpoint accepts a
relative mutation** — no increment, no decrement, no verb that means "flip",
"add one", or "adjust by". The moment one does, two concurrent calls computed
against the same stale read produce a number neither caller asked for, and this
whole decision has to be reopened with `@Version` back on the table.

The pressure point is `#14` (하객별 식사 종류 카운트), where "갈비탕 +1" is the
natural-sounding shape. It must be "갈비탕 2".

## The number still may not move backwards, and that is a different mechanism

"모든 mutation 응답은 재계산된 집계를 싣고 오고, 클라이언트는 순서가 뒤바뀐
응답을 처리한다" is a **client** promise, kept in `web/`, and nothing in this
decision touches it. Two taps whose responses land out of order would write the
older headcount into the query cache after the newer one — that is `#44`'s
ordering guard plus an `onSettled` invalidate, and it is unaffected by anything
above. Do not read points 2–4 as having covered it.

## What this costs

**`#25` (GuestChange 감사 로그) moves from post-v1 into v1.** Scope moved, the
date did not — that is the standing rule, and this is the founder exercising it
deliberately rather than scope arriving by accident.

The cost is smaller than it looks, for two reasons worth stating so nobody
re-litigates it:

- **The table already exists.** `guest_change` is in `V1__baseline_schema.sql`,
  append-only, with both indexes. No migration, no hand-applied DDL, no `#105`
  exposure.
- **Nothing needs retrofitting.** Creation is already recorded on the row
  itself — `guest` carries `created_by` / `created_at` / `updated_by` /
  `updated_at` — so `#11`'s already-merged `POST /weddings/{weddingId}/guests`
  is complete as shipped. `guest_change` records *changes*, and every endpoint
  that changes a guest (`#12`, `#13`, `#14`) is still unbuilt. The rule binds
  them as they are written rather than arriving as rework.

So a create writes no `guest_change` row. One row per field on a create would be
eight rows of noise restating the row next to it, and the question the log
answers — *who changed this* — is answered for a create by `created_by`.

**`#34` (보존 정책) stays post-v1** but now has a trigger it did not have: the
log starts filling at launch instead of whenever `#25` was going to happen. At
one couple and a few hundred guests it is months from mattering.

## When to revisit

- **A relative mutation becomes genuinely necessary.** See the tripwire.
- **Anyone other than the couple can write.** The whole argument that
  last-write-wins expresses a real intention rests on both writers being people
  who share one ledger and can talk to each other. A vendor-email path or an RSVP
  link writing on a guest's behalf breaks that, and the response model returning
  (`#28`) is the same trigger.
- **The couple asks who changed something and cannot find out.** That is the log
  failing at its one job, and it means the write path missed a field.
