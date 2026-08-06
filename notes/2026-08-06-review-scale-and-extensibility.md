# Review — scale and extensibility of the v1 model (2026-08-06)

A check of the model recorded in
[2026-08-06-decision-drop-response-model.md](2026-08-06-decision-drop-response-model.md)
and the notes it builds on, against two questions from the founder: does this hold
up as the numbers grow, and does it extend.

## Scale is not where the risk is

Per wedding:

| Table | Rows |
|---|---|
| `Guest` | 200–600; up to ~800 for a large hotel wedding |
| `GuestMealCount` | guests × types actually used ≈ guests + a bit |
| `GuestChange` | guests × ~5 edits ≈ 3,000 |
| `EmailIngest` | 100–300 |

**No query in this product crosses weddings.** Aggregation always runs inside one
wedding — a ~400-row scan. At 10,000 weddings a year that is 4M `Guest` rows and
30M `GuestChange` rows, which is unremarkable for Postgres with per-wedding
indexes.

The load, such as it is, sits in three places and none of them are the database:

1. **CSV import matching.** A 400-row file matched against existing guests. Written
   naively this is 400 round trips, or worst case 400 × 400 comparisons. **Load the
   wedding's guests once and match in memory.** This is the only operation in v1
   that is easy to get badly wrong.
2. **Round-trip latency on the one-screen design** — see below.
3. **Rendering a long list on mobile.** Needs virtualization at 800 rows. Standard.

### The one-screen decision forces one API shape

"All computation stays server-side" (2026-07-30) plus "the ledger and the headcount
are one screen" (2026-08-06) means every tap costs a server round trip. A couple
tapping through twenty guests makes twenty of them.

The rule is right, but it leaves no choice about the API:

- **Every mutation response must carry the recomputed aggregate.** Otherwise each
  tap costs two round trips instead of one.
- **The client must handle out-of-order responses.** Under fast tapping a stale
  response can arrive late and drag the number backwards.

A number that updates 100ms after the tap is fine. A number that goes backwards is
not.

## Extensibility

### Additive — no existing table is touched

RSVP links returning, seat assignment, 축의금, a new meal type, a new login
provider, a new vendor template. Seating and 축의금 in particular arrive as new
entities *referencing* `Guest`, so the ledger itself never changes shape.

### Fixed now, cheap; fixed later, a migration

**1. Do not use Postgres enum types except where the value set is closed forever.**

`Guest.group_category` changed twice in a single day — 부모님/형제 split, blocked on
조부모, merged into 가족, then 혼주 손님 added. It will change again. As a Postgres
enum, every added value is an `ALTER TYPE` migration.

- **DB enum is fine for**: `side` (신랑/신부 — genuinely closed).
- **varchar + application-level validation for**: `group_category`, `GuestChange.source`,
  `Guest.lifecycle`, `EmailIngest.status`, `OauthIdentity.provider`.

Adding a value should be a code deploy, not a schema migration.

**2. Carry `wedding_id` on every wedding-scoped table** *(proposed; see Still open)*

Of the 13 tables, only two lack it: `GuestMealCount` and `GuestChange`, both of
which currently reach the wedding through `guest_id`.

Four tables are legitimately outside a wedding — `User`, `OauthIdentity`, `Session`
(they belong to a person) and `VendorTemplate` (ours, not a wedding's).

The argument for denormalising: without the column, "does this query respect the
wedding boundary?" is a judgement made per query. With it, "every wedding-scoped
query filters on `wedding_id`" becomes mechanically checkable. In a product holding
guest contacts today and money later, a cross-wedding leak is not an ordinary bug —
it is the failure of 정직함·믿음직함. Worth the redundancy.

Note that **the session does not know the wedding.** Each request resolves
user → membership → wedding, and that is where the boundary is actually enforced.
One person may belong to several weddings, so the wedding must never be pinned to
the session.

## An unplanned gain

`GuestChange` gives time series for free. `Guest`'s expected/confirmed slots only
know *now*, but every change is logged with a timestamp — so **"what was the
expected headcount three weeks ago?" is already answerable.** If a trend view is
ever wanted, no new storage is needed. Dropping the response model ended up
providing more than it removed.

## A tension to expect later

When the RSVP links return, **"every channel converges on one response model" binds
us to the lowest common denominator.** The vendor email gives meal as a party-level
boolean, but our own link could ask a guest for the meal type directly. At that
point the rule likely needs to relax to *"a shared shape plus channel-specific
optional fields"*. Nothing to decide now — recorded so it is recognised when it
arrives.

## Still open

- [ ] Whether to carry `wedding_id` on `GuestMealCount` and `GuestChange`
      (recommendation above; founder's call pending).
- [ ] Screen and flow design, including the import conflict screen at scale.
- [ ] Whether 유아식 counts toward the venue's 보증인원.
- [ ] When the couple configures meal types.
- [ ] Retention policy for `GuestChange`.
