# Decision — v1 records no attribution, and the false premise that hid it (2026-08-22)

Founder's call, made after a premise this repo had been leaning on turned out to
be untrue. `#25` (GuestChange 감사 로그) and `#179` (wedding 감사 흔적) both move
to `post-v1`.

## 0. The premise that was false

Three documents said, in one form or another, that **`guest_change` already
records who changed what.** It does not.

`guest_change` is a table in `V1__baseline_schema.sql` and **nothing writes to
it.** There is no `GuestChange` entity and no repository. Every mention of the
name in `api/src/main/kotlin` is a comment, and one of them says so outright —
`GuestService.kt`: *"No `GuestChange` row. The audit log holds one row per changed
FIELD with an old value and a new one, which a creation has none of, and its write
path is `#25`'s."* `#25` was open and unbuilt.

So the true state was not "guests have a trail and the wedding does not". It was
**nothing in this product records who changed anything**, and `#179` was the
wedding half of a question `#25` already owned for guests. Framed that way it is
one decision, not two, which is why it is recorded once.

**Where the false premise was load-bearing**, listed so the correction reaches
everything that leaned on it:

- `2026-08-21-decision-two-accounts-and-the-v1-recut.md` §0 gives "`guest_change`
  already records *who*" as one of three reasons the two-accounts correction cost
  hours rather than days. As a statement about the *model* it is true; as a
  statement about the running system it is not. **That record is amended with a
  banner** rather than rewritten.
- `#159` (마이페이지) argued for v1 status on the grounds that "an edit made from a
  partner's session is silently attributed to them". It is attributed to nobody.
  **`#159` stays v1 anyway** — and the reasoning inverts rather than weakens: with
  no record, there is nowhere to look afterwards, so a screen saying who you are
  *while you edit* is the only thing standing in that gap.

## 1. The call

**v1 records no attribution.** Both `#25` and `#179` are `post-v1`.

The question put to the founder, once the premise was corrected, was not about
tables. It was: **will you ever want a screen that answers "who changed this?"** —
because the write has to precede the read by however long v1 runs.

## 2. Why deferring is defensible

- **v1 would write rows nobody can read.** The screen that answers "이 하객을 누가
  마지막으로 고쳤나" is `post-v1` and was already so before today
  (`2026-08-21-decision-two-accounts-and-the-v1-recut.md` §4). Building the write
  path in v1 buys nothing a v1 couple can see; it only makes the launch period's
  history exist for a reader that does not.
- **The v1 ledger has two editors and they are married to each other.** The
  question "did you change this?" has someone to ask. That is not true of the tool
  at scale, and it is exactly why this is deferred rather than dropped.
- **Nine days.** Two tables' write paths, threaded through `POST /guests`,
  `PATCH /weddings/{id}` and everything `#12` and `#13` add, is not a small change
  arriving late.

## 3. What it costs, stated because it is permanent

**The launch period will never be attributable.** An audit log is not
retroactive: whatever a couple changes between launch and the day `#25` ships has
no author, and no later work recovers it. Every other deferral in this project
costs a feature that can be added; this one costs *history*, which cannot.

The sharpest instance is 보증인원 — **money**, edited by two accounts, with no
trail. A couple who finds 150 where they remember 170 has nowhere to look, and
this is the product whose first value is 정직함 · 믿음직함.

That cost is accepted knowingly. What makes it acceptable is §2's second bullet
and nothing else: **the two people who can change it can ask each other.** The day
that stops being true — a planner with access, or a wedding whose parents edit —
is the day this reopens, and it reopens as work, not as recovered history.

## 4. What does not change

- **The tables stay.** `guest_change` ships in `V1` and is untouched; `#179`'s
  wedding table is simply not written yet. Deferring costs no migration and
  restoring costs no migration.
- **`guest_change` remains append-only and is never soft-deleted**
  (`2026-08-10-decision-soft-delete.md`) — that rule was about the table, not the
  write path, and it survives.
- **Every design record describing `GuestChange` as the traceability mechanism
  stays correct.** They describe the design; only claims about what the running
  system *does today* were wrong, and those are enumerated in §0.

## 5. The trigger to reopen

Any of: a third party editing the ledger (planner, parents), a couple asking us
who changed a number, or `#31` (축의금) landing — money with an owner is a
different risk from money with a count.

Refs `#25`, `#179`, `#159`, `#173`, `#12`, `#13`,
`2026-08-21-decision-two-accounts-and-the-v1-recut.md`,
`2026-08-10-decision-soft-delete.md`,
`2026-08-06-decision-drop-response-model.md`
