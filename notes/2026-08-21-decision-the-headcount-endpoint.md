# Decision — what the headcount reads, and where it is computed (2026-08-21)

`#151`, the backend half of `#17`. Two of the four things below were left open by
records that are already merged and would be re-argued by the next person to read
them, so they are settled here rather than in a KDoc.

`2026-08-21-decision-attendance-is-two-states.md` is the founder call this is built
on and is not restated: 참석 여부는 참석·불참 둘뿐이고, **미확인 멤버는 없다 — null이
아니라 아예 없다.**

## 1. The number gates on attendance, through the ledger's own fallback

`2026-08-20-decision-the-ledger-read-and-its-filters.md` §3 ends with "**What the
headcount reads first** — asked, not answered here", and states the constraint the
answer has to satisfy: 원장과 인원수는 한 화면이므로 a guest under the 참석 chip may
not be a guest the number treats as 불참.

**Answered: attendance first, read through `coalesce(confirmed_attending,
expected_attending)` — the same expression the ledger filter uses — and the party
size of a 불참 guest contributes zero** (`2026-08-20-decision-guest-entry-side-and-
companions.md` §3, and `V1__baseline_schema.sql`'s own words on `guest`: a party
size on a 불참 guest is stale data, not a claim).

The `coalesce` needs a word, because under two-state attendance it looks like a
column nothing writes. **It is not a second reading of attendance; it is the same
reading**, and the choice was never between two numbers — with `confirmed_attending`
permanently NULL the two spellings are the same number in every row, today and for
all of v1. What the choice is between is two *shapes*:

- one axis, one expression, read identically by the chip and by the number — so if
  the second slot ever returns, both move together;
- or two spellings of one axis, identical until the day something writes the
  confirmed slot, and then silently disagreeing on one screen.

The founder's record already chose the first for the merged filter, on exactly this
ground ("that is the shape that survives if the second slot ever returns"), and a
new query on the same axis has no claim to a different one. **What the test holds is
the agreement, not the spelling**: it writes a confirmed value through JDBC — the
only writer there is — and asserts that the guests under `?attendance=ATTENDING` and
the number this endpoint returns are the same set of people. A headcount that read
`expected_attending` alone fails that test, which is the point.

## 2. 보증인원 is absent when it is not set, never null and never derived

`wedding.guaranteed_headcount` is nullable and nothing fills it until `#8`. The
response then **omits the member**, exactly as the mutation envelope omitted
`headcount` before this stop (`2026-08-20-decision-mutation-response-envelope.md`):
absent says "this response does not carry that", which is true, where `null` says
"we looked and there is no number", which is a claim about a venue contract we have
never seen.

**대비 계산은 클라이언트의 뺄셈이다.** We do not send a difference, a percentage or a
recommendation — 보증인원 is the venue's number and we do not produce numbers we do
not know (root `AGENTS.md`).

## 3. The aggregate is computed in `guest/`, and `wedding/` gains a read contract

The number is the wedding's, so `wedding/` looks like its home. It cannot be:
`guest/` already depends on `wedding/` (`WeddingScope`, `WeddingSide`), and a
`wedding/` that summed guests would close the loop — `ArchitectureTest`'s slice rule
refuses the cycle, and it is right to. **The arrow between these two packages is
already spent, so the aggregate lives on the ledger's side of it.**

That leaves 보증인원, which `guest/` may not read the way it would be tempting to:
the cross-domain rule forbids touching another domain's entity or repository. So
`WeddingService` gains the **declared read contract** the first-endpoint record
predicted would eventually be needed
(`2026-08-17-decision-first-domain-endpoint-shape.md`), and `Wedding.guaranteed_
headcount` stops being an unmapped column. `#8` writes it; this only reads it.

## 4. What `#14` changes here, stated now so it is not rediscovered

Today the sum is `expected_party_size`, because `guest_meal_count` has no rows and
`#10`/`#14` are not started. When per-meal-type counts land the rule is **"the
guest's rows if they have any, else the party size"** — a guest with no rows is a
default, not a zero (`2026-08-06-design-ledger-and-import.md` §4).

And the query changes shape, not just terms: `guest_meal_count.wedding_id` is an
integrity column and **never a predicate**. The read joins `guest` and filters
`guest.deleted_at`, or it counts the meals of deleted 하객 — silently, because
`@SQLRestriction` does not reach a native query, and over-counting 보증인원 is money
(`api/AGENTS.md`, Domain mechanisms; `GuestMealCountSchemaTest`).

**유아 인원 is not inside this number** and never becomes a third member of it by
default (`2026-08-11-decision-deletion-and-infant-meals.md`); it stands beside the
식대 인원 when `#10` gives meal types a screen.

## What this does not decide

- **The 참석 토글** (`#13`) — this endpoint only reads. Its write is what makes the
  number move in place, and the envelope is already there to carry it.
- **The 보증인원 write path** (`#8`), and therefore whether the couple ever sees a
  comparison at all before it.
- **Whether the ledger read starts carrying the aggregate.** It does not
  (`2026-08-20-decision-the-ledger-read-and-its-filters.md`); 원장 화면 reads two
  endpoints, and that stays true.

Refs `#151`, `#17`, `#8`, `#13`, `#14`,
`2026-08-21-decision-attendance-is-two-states.md`,
`2026-08-20-decision-the-ledger-read-and-its-filters.md`,
`2026-08-20-decision-mutation-response-envelope.md`,
`2026-08-20-decision-guest-entry-side-and-companions.md`,
`2026-08-05-design-meal-headcount.md`.
