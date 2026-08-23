# Decision — what a guest entry requires, and that companions follow the head (2026-08-20)

> **Superseded in part (2026-08-23).** §2 chose to keep companions as a
> count rather than rows, and named what that gave up. The founder has since
> asked for those things back: a companion is now its own 하객 record.
> See `2026-08-23-decision-companions-become-guests.md`. §1, §3 and §4 stand.

`#134`/`#11`. The founder settled four questions the first guest-write endpoint
raised. Three of them are about defaults; the fourth is a model choice wearing a
default's clothes.

## 1. 측 is chosen at entry, and has no default

`#11` said "필수는 이름 하나". This narrows it: **name and 측.**

`groupCategory` can afford a default because it has a residual member — `기타`
honestly means "not stated yet", so a guest with no group lands somewhere true.
`wedding_side` has two values and no residual. Whatever we default to is not a
blank, it is **a claim the couple never made** — and 측 is one of the ledger's two
filters and an aggregation axis, so the claim becomes a number. This is the same
rule that keeps us out of 보증인원 and 유아식 pricing: we do not supply numbers we
do not know.

**It stays editable** (`#12`, `#8`). Required at entry is not required forever.

The reversal cost runs one way, which is why required is the safe side to start on:
relaxing the *request* later is additive and breaks no client, but `GuestResponse.side`
is a two-value union and the column is `not null`, so ever *emitting* "미정" is a
frontend break plus a migration. Relaxing the API alone would not create an
unstated state — it would only move the guess from the couple to us.

## 2. Companions follow the head, and that is why they stay a number

`expected_party_size` is a count, not rows. The founder's rule — **a companion
takes the head guest's 측, and a head marked 불참 marks the companions 불참 too** —
is what makes that representable. A number has no 측 and no attendance of its own,
so "follows the head" is not a rule the code enforces; it is what the model already
says. Choosing the rule *is* choosing not to give companions their own rows.

**What that gives up, stated plainly**, because it will come back:

- A 신랑측 guest bringing someone the couple thinks of as 신부측 cannot be split.
- A head who cannot come while their companion still attends cannot be expressed.

The escape hatch is the ledger itself: **register that person as their own guest.**
Direct entry is the primary intake path, so this costs one row, not a feature.

## 3. Attendance is read before party size — for the expected slots too

`V1__baseline_schema.sql` already ruled this for the *confirmed* slots: "a party
size on a 불참 guest is stale data, not a claim", leaving the precedence to `#17`.
The founder's answer extends it to the expected slots. **A guest marked 불참
contributes zero to the meal headcount regardless of party size.**

The value is kept, not erased. A guest whose attendance flips back to 참석 brings
their party size with them; erasing on write would make that flip silently reset
the count to 1, and the couple would have to retype something they had already told
us.

## 4. The two remaining defaults, now actually decided

`2026-08-06-design-ledger-and-import.md` §4 holds exactly two defaults — 참석, and
meal count following party size. Two more had been shipping while citing that
section, which does not contain them. They are decided here:

- **Expected party size defaults to 1.** A guest is one person until told otherwise.
- **A guest entered with no group lands in `기타`**, including one who typed a free
  label but no category. The label is kept and shown; only the aggregation reads the
  category.

The second one is in tension with §1 of that record, which created `혼주 손님`
precisely so `기타` would not become the wedding's largest block. Accepted knowingly:
refusing a couple who typed the useful half is worse, and the fix belongs in the
sheet's affordance rather than in a rejection.
