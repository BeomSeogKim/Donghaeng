# Decision — a companion is a guest, and the ledger folds them (2026-08-23)

**This reverses §2 of `2026-08-20-decision-guest-entry-side-and-companions.md`**,
which chose to keep companions as a count. That record named exactly what the
choice gave up; the founder has now asked for those things back, and this record
says what it costs to buy them.

## The ask, in the founder's words

> 인원수로 설정하는 것 좋음. 다만 이렇게 했을 때 추가 인원에 대해서는 가상 이름이
> 주어졌으면 하고 (추후 수정으로 이름 입력이 가능해야함), 동반인원의 주체가 누군지
> 알았으면 함

Three things, and only the first is about entry. Entering a party as a number
stays. What changes is what the number *becomes*: **a party of three is three
하객 records**, not one record carrying a `3`.

## 동반인원 is the same 하객 object

Asked directly whether companions should be their own type, the founder settled
it: **동일한 `하객` 객체로 다룬다.** So there is no `attendee` beside `guest`.
A companion is a guest that points at the guest who brought it.

What that buys back is precisely the two losses §2 listed:

- A 신랑측 guest bringing someone the couple reads as 신부측 can now be split.
- **A head who cannot come while a companion still attends is now expressible** —
  and this is the case the ledger had no way to state at all.

What it costs is that the two rules §2 leaned on stop being free. "A companion
takes the head's 측" and "a head marked 불참 marks the companions 불참" were not
enforced by code; they were what the model already said. They are now defaults
applied at creation, and the couple can diverge from them afterwards. **A rule
that used to be a fact is now a default**, and that is the real price.

## The name is given, not asked for

A companion is created with a name the couple did not type — `박영희 동반 1` —
and it is **editable afterwards, never re-generated.** The generated name exists
so the row is addressable at all; the moment the couple types over it, it is
theirs. The 주체 is carried in the name and in the reference, so a companion
surfaced on its own — by a search, by a filter — still says whose it is.

## The ledger folds, and it does not guess

The screen shows **one row per party**, with a disclosure on any party of two or
more; the 인원 column carries the party total. Expanding shows the people, each
holding its own 참석.

The collapsed row's 참석 column has three readings, and the third is the point:

- all attending → `참석`
- none attending → `불참`
- **mixed → `3 / 4`**, and pressing it expands the row rather than picking one

That third state is the standing rule — ambiguity is never guessed — applied to
a control instead of to an import. A mixed party has no attendance state, so the
screen states the count it does know and hands the decision back.

## The headcount stops being a sum of party sizes

식대 인원 becomes **the count of attending 하객 records**. It is the same number
for every party that agrees with itself, and a truer one for every party that
does not. The alternative — summing party sizes and subtracting absent
companions — is the old model wearing the new one's clothes.

## Scope

This is v1. It is a schema change (`expected_party_size` leaves; a self
reference arrives), an API change, and a ledger change, so it is a parent issue
with a backend child and a frontend child, backend first because the spec is the
seam.
