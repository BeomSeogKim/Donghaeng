# Decision — the couple's two seats: `membership` and the two name columns were one concept (2026-08-22)

The founder, on first seeing 웨딩 만들기 in a browser: **신랑 이름과 신부 이름은
사람의 속성이지 웨딩의 속성이 아니다.** That reading is right, and following it
found something larger than a screen problem — so this record settles the model,
not the form.

`V1` held one concept in two places and joined neither to the other:

| The seat's | Lived in |
|---|---|
| name | `wedding.groom_name`, `wedding.bride_name` — two strings |
| account | a `membership` row |
| **the link between them** | **nowhere** |

Two consequences follow, and they are why this is a re-shaping rather than a
rename. **"This account is the 신부" was not expressible** even with both accounts
present — `membership` carries no role, so with two rows the product still cannot
say which is which. And **onboarding asked one partner to type the other's name**,
because a required field had to be filled by whoever arrived first.

**What the two name columns bought, measured rather than assumed**: across all of
v1 they are read in exactly one place, `web/src/pages/LedgerPage.tsx:259`, as the
subtitle telling the couple which wedding is open. No `notes/` record references
them. The vendor-email parser does not use them. **The ledger's 신랑측/신부측
filter has nothing to do with them** — that is `guest.side`, an attribute of the
guest. Two required fields, one of them a person's own partner's name, returning
a subtitle.

## 1. A wedding has two seats, and a seat is the thing that was missing

`wedding_party` (`V3__wedding_core.sql`). One row per seat, **two rows per
wedding, always**: `side`, `name`, `user_id`, `joined_at`.

A seat carries a person's name and — once they arrive — their account. Both were
already in the model as fragments; the seat is what they were fragments of.

**`membership` is dropped rather than given a `role` column.** The alternative was
available and cheaper: add `role` to `membership`, leave the names on `wedding`.
It was refused because it leaves the split intact — a person's name in one table,
their account in another, and now a role bridging them. The founder's words for
that shape were **덧대기**. The test that decides between them: after the change,
is there exactly one place that answers "who is the 신부 of this wedding"? With a
role column there are two, and they can disagree.

**`user_id IS NULL` is the load-bearing state.** It means the seat is waiting,
which is how a wedding created by one person is complete rather than half-built.
The couple are still two accounts on one ledger
([2026-08-21-decision-two-accounts-and-the-v1-recut.md](2026-08-21-decision-two-accounts-and-the-v1-recut.md));
what changes is that the second account now has a place to land that exists before
it does.

**`deleted_at` stays on the seat**, unlike `wedding_subscription`, whose terms end
rather than being removed
([2026-08-22-decision-entitlement-belongs-to-the-wedding.md](2026-08-22-decision-entitlement-belongs-to-the-wedding.md)).
A seat has to be *releasable*: `wedding` is soft-deleted rather than removed, and
without a `deleted_at` its seats would go on occupying `ux_party_user` and bar
both people from ever joining another wedding. Clearing `user_id` instead would
also release them, and would lose who held it.

## 2. Both seats are created with the wedding, so an invite fills one

`POST /weddings` creates the wedding, **both seats**, and the free subscription
term in one transaction. The caller's seat gets their side, their name and their
`user_id`; the partner's seat gets a `side` and nothing else.

The alternative — create one seat now, the second when the partner accepts — was
refused for what it does to `#9`. **An invite has to name which seat it is for**,
and a token pointing at a row that does not exist yet must instead carry the side
itself, re-deriving on acceptance what the wedding already knew. Creating both
makes the invite an `UPDATE` of one identified row, which is also what makes
"two people accepting the same link" a lost update rather than a duplicate
membership.

It also makes the invariant total and checkable: **every wedding has exactly two
seats from the moment it exists.** `ux_party_wedding_side` enforces at most one
per side; the creating transaction is what supplies "at least".

The request changes shape without changing size:

```json
{ "weddingDate": "2026-10-10", "side": "GROOM", "name": "김신랑" }
```

Three fields still, but the third is **who the caller is** rather than who their
partner is.

## 3. `#158`'s rule moves; it is not re-decided

**한 사람은 웨딩 하나** ([2026-08-21-decision-one-wedding-per-person.md](2026-08-21-decision-one-wedding-per-person.md))
holds unchanged. `ux_membership_user` becomes `ux_party_user` — same rule, same
partial-index mechanism, one new clause:

```sql
create unique index ux_party_user on wedding_party (user_id)
    where deleted_at is null and user_id is not null;
```

`user_id is not null` is what makes an unclaimed seat legal. Multiple NULLs do not
collide in a partial index anyway; the clause is spelled out because a reader has
to see that the empty seat was intended and not tolerated.

Everything §2–§5 of that record decided — the 409, the lock, the recovery being
`GET /weddings` rather than a retry — applies to the seat exactly as it applied to
the membership. **The refusal's meaning is unchanged**: the caller already holds a
seat somewhere.

## 4. Parents and planners are 지원자, and that is why merging is safe

The question that gated this decision: **is a wedding's account membership forever
just the two of them?** If parents or a planner might one day hold accounts, then
"member" and "seat" are different concepts and merging them would have to be
undone later, with data in the tables.

The founder's answer, and the sentence that settles it:

> 주체는 예비 신랑, 예비 신부이다. 부모님, 플래너 등등은 **결혼 지원자** 느낌이지
> 구성 요소 자체는 아니다.

A supporter is **not a subject**, and the distinction is structural rather than a
matter of degree. Seats are exactly two, permanent, and own the ledger.
Supporters would be zero-to-many, narrowly scoped, and come and go. Different
cardinality and different lifetime means **a different table standing beside this
one** — never a split of it. So the merge forecloses nothing, and nothing is built
for supporters today.

This matters more than it looks, because parents are already in the domain: 참석
여부 reaches the couple through parents and KakaoTalk, and the import template is
handed to parents to fill. **They are in the product without being in the
model**, and that is the correct arrangement.

## 5. What this does not decide

- **Whether a seat can be released and re-invited, and what that leaves behind.**
  `deleted_at` and a nullable `user_id` both permit it; which one partner removal
  uses belongs to `#9`, where there is a flow to reason about.
- **What the ledger header shows for an empty seat.** It shows what it has; the
  copy is the frontend's, on the screen where it is visible.
- **Whether `wedding.created_by` still earns its place** now that the creating
  seat records the same person. Left alone deliberately: it is an audit stamp
  shared with `guest` and `email_ingest`, and removing it is a separate argument.

## 6. Cost, stated because it was weighed

`api/` main touches ~11 files plus `V3`, `api/` tests ~13, `web/` 5. Most of the
test surface is fixtures — names change, rules do not. **No data migration**: zero
users, zero deployments, and the one local wedding was test data, dropped rather
than converted because nothing recorded which seat its single member held.

Done nine days before a fixed launch, with `#9` (v1) about to touch this exact
area anyway. Deferring means adding `role` to `membership` in `#9` and then
re-doing it, and it means paying with a migration once real couples exist. **The
price of this change never gets lower than it is today.**
