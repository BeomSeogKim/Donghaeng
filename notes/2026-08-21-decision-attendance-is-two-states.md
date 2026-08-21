# Decision — attendance has two states, and the 미확인 count goes with the second one (2026-08-21)

Founder's call, made while `#17` was being specified. It reverses two earlier
design calls, so it is recorded before anything is built on either side of it.

## The call

**참석 여부는 참석 · 불참 둘뿐이다.** There is no second attendance slot in v1 —
no "the couple's guess" versus "what was actually verified", and therefore no
guest who is 참석 *and* 미확인 at the same time.

## What it reverses, and why that needed a founder

`V1__baseline_schema.sql` gives `guest` two slots for each of attendance and
party size — `expected_*` (NOT NULL, the couple's own value) and `confirmed_*`
(nullable, "blank is UNKNOWN, never zero"). Two design records were built on that
second slot:

- `2026-08-05-design-meal-headcount.md` §1 — the headcount is one number with
  **아직 모르는 N명** shown beside it, N being the guests with no confirmed value.
  That N was the whole uncertainty disclosure.
- The same record §4 — **"응답률" was replaced by "미확인 인원"**, on the argument
  that response rate measures our channels rather than the couple's problem.

Both are now withdrawn. **The screen shows 식대 인원 and 보증인원, and nothing
else.**

The question that settled it was not "should the schema have two slots" but
whether a couple actually keeps two answers in their head — one they were told and
one they wrote down. Told the distinction in those terms, the founder's answer was
to collapse it. That is a fact about how couples use the ledger, and nothing in
these notes could have produced it.

## What it costs, stated because it will come back

At the 1–2 week 보증인원 decision, **a 387 where every guest was verified and a 387
where half were guessed look identical.** That difference is money, and this is the
product whose first value is 정직함 · 믿음직함, so the cost is real and it is being
accepted knowingly rather than overlooked.

What makes it acceptable: the disclosure only ever worked if the couple maintained
the second slot, and a slot the couple does not maintain does not disclose
uncertainty — it manufactures a false one. A 미확인 count that is always equal to
the guest count is worse than no count at all.

**The trigger to reopen it**: real couples asking "이 숫자 중 몇 명이 확실한
거냐". The columns are still there, so reopening costs a write path and a screen,
not a migration.

## What it means mechanically

- **`expected_attending` is the attendance**, full stop. `#13`'s 참석 토글 writes
  that column. `confirmed_attending` and `confirmed_party_size` are **never
  written in v1**.
- **Nothing already merged has to change.** The ledger's 참석 filter reads
  `coalesce(confirmed, expected)`
  (`2026-08-20-decision-the-ledger-read-and-its-filters.md` §3); with the confirmed
  slot permanently NULL that is exactly `expected`, so the merged predicate is
  already correct. It stays in the `coalesce` shape rather than being simplified —
  that is the shape that survives if the second slot ever returns, and simplifying
  it would buy nothing today and cost a re-derivation later.
- **The columns are not dropped.** A migration ten days before launch buys nothing;
  they are nullable and inert. What they do need is a spec line saying v1 never
  writes them, so no one builds on a slot nothing fills.
- **`#17`'s aggregate is two numbers**: 식대 인원, and 보증인원 (nullable until
  `#8` gives it a screen). No `unknownGuests` member — absent, not zero.

Refs `#17`, `#13`, `#15`, `#8`,
`2026-08-05-design-meal-headcount.md`,
`2026-08-06-design-ledger-and-import.md`,
`2026-08-20-decision-the-ledger-read-and-its-filters.md`,
`2026-08-20-decision-mutation-response-envelope.md`
