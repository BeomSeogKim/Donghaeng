# Decision — four calls on the v1 baseline schema (2026-08-11)

Review of `#3` surfaced four questions the schema could not answer for itself.
All four are the kind that is cheap only **before the DDL is typed into a real
database by hand** (2026-08-09), which is why they were settled as a batch
rather than deferred.

Recorded here because an issue never decides anything (2026-08-08). Each
section states the call, then why the alternative lost.

## A — only a provider-verified email is a merge key, and v1 has no account linking

**The call.** An email is written to `app_user.email` only when the provider
**asserts it as verified**. Otherwise the column is NULL and that account
stands alone. **There is no account-linking flow in v1.**

`2026-08-06-design-ledger-and-import.md` §3 established that the same person
arriving via a second provider is merged by email. That decision survives,
**narrowed to verified emails.** The narrowing is not a refinement — without it
the merge is a full ledger takeover:

Kakao returns `is_email_verified` as a field *separate* from the address and
can hand back an unverified one; Naver's is user-editable. So an attacker signs
up to Kakao claiming the victim's Gmail address, logs into 동행, and the merge
logic seats them on the victim's `app_user` row — with the victim's
memberships, and therefore the whole ledger including every guest's phone
number. **No token, no expiry, no invite.** The invite token was tightened to
single-use and 72 hours for being the most dangerous thing in the system; this
path grants the same access with none of that.

**Why not an account-linking flow.** The third option on the table was to
neither merge nor silently split, but to tell the person — "이 이메일로 가입된
계정이 있습니다, 원래 쓰시던 방법으로 로그인하시면 연결해 드릴게요" — using a
login with the original provider as the proof. It is the industry-standard
answer and it removes the one real cost of splitting. The founder's call is
that v1 does not build it: it is a genuine flow in `#37`, and the case it
serves is narrow.

Worth being precise about how narrow, because it is easy to overestimate. Two
partners each using their own provider is **not** a merge case — that is two
accounts and two memberships on one wedding, which is the design. The only case
is **one person switching providers between visits**: Kakao in March, the
Google button in June. Then they see an empty ledger.

**Schema consequences** (all in `V1`):

- `email_verified_by varchar(20)` — which provider vouched. Without it,
  "we merged two accounts" has no record of whose word we took, and the
  narrowing above is unverifiable after the fact.
- A CHECK that the pairing is total in both directions:
  `((email is null) = (email_verified_by is null))`. Its reach is limited and
  the file says so — it proves the verifier was *recorded*, not that Kakao's
  flag was actually read. That part is `#37`'s; this makes forgetting it loud.
- **A CHECK on the verifier's value set: `in ('GOOGLE', 'KAKAO')`** — added
  after the security re-audit, because the biconditional above turns out to
  make the takeover *convenient*. It demands a verifier whenever an email is
  present, so a `#37` developer holding a Naver address chooses between
  dropping the address and writing `'NAVER'` — a statement that can never be
  true, and one that reopens §A in full. This is the single place where the
  project's "adding a value is a deploy, not an `ALTER TYPE`" rule cuts **for**
  a constraint: every other open value set is open because a new value is just
  a new word, whereas a name in this list is a claim that a company checked
  mailbox control, so the `ALTER` is the point.
- **A CHECK on the email's shape:**
  `email is null or (email like '%_@_%' and email !~ '[[:space:]]')`. Same
  re-audit. `''` is a legal varchar, so a provider that returns `""` for an
  absent optional field instead of omitting it writes an empty *verified*
  address — and every later empty-email login then merges onto it. That is not
  a split; it is **one `app_user` shared by strangers**, each seeing the
  others' weddings, guests and contacts, with whoever registered first holding
  the row. The whitespace half is the same argument one step out
  (`' kim@gmail.com'` and `'kim@gmail.com'` are two people to the schema).
  Normalising `""` to NULL and trimming stays `#37`'s; this makes forgetting
  it loud.
- **The unique index is on `lower(email collate "C")`**, not `email` and not a
  bare `lower(email)`. The `lower()` is independent of everything above and is
  simply a bug otherwise: Google returning `Kim@Gmail.com` and Kakao returning
  `kim@gmail.com` splits one person into two accounts, which to the couple
  looks like the ledger vanished — the loudest possible failure for a merge
  key. The **collation** was added by the re-audit, and it is not cosmetic:
  under the database's own ctype `lower()` is not injective
  (`lower('KİM@X.COM') = lower('KIM@X.COM')` is true while the addresses are
  not equal), so two people share one key and the second is seated on the
  first's account. `"C"` makes the fold ASCII-only and pins the btree to byte
  order, which also removes the risk of a glibc/ICU upgrade silently
  invalidating a unique index on the merge key. No provider issues verified
  non-ASCII addresses today, so what this defends is **the drift** — and
  collation is the same class as the `ddl-auto: validate` gap this schema is
  typed under, only worse: validate misses a *width*, collation changes an
  *identity*.

**This creates an obligation on `#37`, filed as `#82`.** The index makes a
duplicate impossible; it does not make the *merge* work. A lookup written as
`where email = ?` misses the existing `Kim@Gmail.com` row, takes the
create-account branch, and then hits a unique violation — turning a silent
split into a hard 500. The collation widens that obligation: the lookup must
use the **same expression the index is built on**, and the normalisation must
be an **ASCII-only lowercase** — Kotlin's `String.lowercase()` is full Unicode
case mapping and is therefore *not* `lower(... collate "C")`.

## B — a forced re-import is recordable, via `superseded_at`

**The call.** `guest_import` gains `superseded_at timestamptz`, and the hash
index narrows to live rows:

```sql
create unique index ux_guest_import_wedding_file
    on guest_import (wedding_id, file_hash)
    where status = 'SUCCEEDED' and superseded_at is null;
```

**The conflict it resolves.** The index as first written made at most one
`SUCCEEDED` row per `(wedding_id, file_hash)` **ever**, so a deliberate re-run
of the same file could not be recorded at all — the second insert died on the
constraint. That collides head-on with the soft-delete decision taken the day
before (2026-08-10), whose "삭제된 하객이 파일에 있으면 되살릴지 묻는다"
question can only fire on a file that is actually *processed*. And the
commonest case by far is a parent re-sending the identical file, whose hash
matches and which is therefore never processed. The new question would have
been unreachable in exactly the situation it exists for.

**How forcing works.** Two statements in one transaction: stamp
`superseded_at` on the live row, then `UPDATE` the already-committed
`RECEIVED` row to `SUCCEEDED`. The invariant is unchanged — at most one *live*
`SUCCEEDED` row per hash.

**But the index does not serialise two concurrent forces; the ORDERING does**,
and this was stated wrongly the first time. The first version of this section
claimed the TOCTOU protection carried over to forced runs unchanged. It does
not carry over for free — it carries over only under a writer contract that had
not been written down, and the reviewer reproduced the failure on Postgres 16.

What serialises is the row lock the supersede `UPDATE` takes on the live row.
So the supersede must be the **first statement of the processing transaction —
before the matcher loads the wedding's guests**, not merely before the flip.
Then the loser blocks, the winner commits, `READ COMMITTED` re-checks the
predicate against the updated row, and the loser gets `UPDATE 0` before reading
a single guest.

With the supersede next to the flip instead — the natural place for
bookkeeping, and what the unqualified claim invited — **both transactions
commit.** The loser's supersede runs after the winner has committed, resolves
the winner's brand-new row as the live one, supersedes *that*, and finds an
empty slot for its own flip. The index invariant holds perfectly, exactly one
live `SUCCEEDED` row, and every guest in the file is written twice from a
matcher snapshot taken before the winner's guests existed. That is the same
double write the second rejected alternative below is rejected for.

**And `0 rows superseded` aborts the import**, which matters as much as the
ordering. A writer that reads `UPDATE 0` as "nothing is live, so re-resolve and
supersede whatever is live now" reaches the identical double write by a second
route. Zero rows on a force means another transaction took the slot while we
waited; the premise of the force is gone.

Both halves are now stated on the index in `V1`, which is where a reader meets
them before the service. They bind `#20`.

**Two alternatives lost, and both will be proposed again:**

- **`forced boolean` in the index key** — allows exactly two rows (`false`,
  `true`). The second force is blocked again.
- **Exempting forced rows from the index** — reopens the concurrent
  double-submit, which writes 380 guests twice.

**The honest tension.** Stamping `superseded_at` mutates a row on a table this
project declared append-only (2026-08-10: import records get no `deleted_at`,
because deleting one breaks hash idempotency). The reading under which this is
consistent: the original row's own fields are untouched, and the stamp appends
a **later fact** — "this import is no longer the authority for this file" —
rather than revising what happened. That is derived state about which row is
current, not a claim about the event.

**This is the column and the index only.** Whether a force button ships, and
what it says, belongs to `#20`/`#21`.

## C — `guest_meal_count` gets `wedding_id`, as an integrity device only

**The call.** `guest_meal_count` carries `wedding_id bigint not null`, and its
two single-column FKs become composite: `(guest_id, wedding_id)` → `guest`,
`(meal_type_id, wedding_id)` → `meal_type`.

**The hole.** A row joining wedding 1's guest to wedding 2's meal type inserted
cleanly — the reviewer did it. `meal_type_id` arrives in the request body at
`#14`, so it never passes through `CurrentWedding` resolution. Worse, the
outcomes differed: a nonexistent id was an FK violation, another wedding's id
succeeded. **A difference in response to an id you supply is an oracle** — the
same reason a cross-tenant request is 404 and not 403 (2026-08-10).

The aggregate numbers were never at risk *before* this column existed (they had
to join through `guest.wedding_id` and filter). What was reachable is the write
path and the "is this meal type in use?" check — the latter meaning one guest in
a stranger's wedding could make our meal type permanently undeletable.

**The column creates one new hazard, and it is the expensive kind.** It makes
`select sum(expected_count) from guest_meal_count where wedding_id = ?`
writable, and that query counts the meals of soft-deleted 하객: `@SQLRestriction`
does not reach a native query (2026-08-10, consequence 1), so it does not throw,
it **over-counts** — and over-counting 보증인원 is money. The soft-delete record's
warning was written before this table had the column, so it did not name the
place it bites hardest. The rule, stated on the column in `V1` and held by
`GuestMealCountSchemaTest`: **`wedding_id` here is an FK component and never a
query predicate; every read joins `guest` and filters `deleted_at`.** It binds
`#17`.

**Why the schema and not a test.** The alternative was leaving the schema alone
and adding a Testcontainers test at `#14` asserting 404. This codebase's idiom
is that **a thing that can be forgotten becomes a default**, and the composite
FK makes the bad row *unrepresentable* — there is no `wedding_id` value that
satisfies both parents at once.

**What the FKs do not close, added by the security re-audit.** They force the
row to be *internally consistent*, not to be **owned** by the caller's wedding.
A row lying wholly inside another wedding — `(wedding_id, guest_id,
meal_type_id) = (2, 200, 20)` issued by a wedding-1 caller — inserts cleanly,
because all three columns agree. The plausible `#14` implementation ("load the
guest, take its `wedding_id`") writes exactly that row into a stranger's
ledger, and the second identical attempt then returns a unique violation where
the first returned success — a cross-wedding **existence oracle** on the
victim's guests, which is what 2026-08-10 denies. **Only the resolver closes
this** (`#5`/`#14`); no constraint on this table can. The migration comment says
so at the FKs, because "unrepresentable" invites the wider reading.

**And they do not imply a live parent.** Both targets are non-partial unique
constraints by necessity, so a soft-deleted `guest` and a soft-deleted
`meal_type` each still accept new `guest_meal_count` rows. Same wedding only, so
it is not an isolation problem — but "a type in use cannot be deleted" and the
soft-delete filter have no database backstop here and `@SQLRestriction` does not
reach. That is a test on `#10`/`#14`, not a schema change.

### This amends a standing rule

"Every wedding-scoped aggregate root carries `wedding_id`; anything reached only
through its root does not" (2026-08-06) made `wedding_id`'s presence a
*mechanically checkable* signal of roothood. `guest_meal_count` is still not a
root, and this column does not make it one.

**So: a `wedding_id` present for integrity is not a root marker.** The
distinction is checkable rather than arguable — an integrity-purpose column
appears in a composite FK to a parent's `(id, wedding_id)`; a root's does not.
`#80` (the `wedding_id` allowlist meta-test) must carry this as an explicit
exception, and the migration comments say so where a reader will find them.

**Accepted costs.** Two extra non-partial unique constraints on `guest` and
`meal_type` — non-partial by necessity, since Postgres will not point an FK at
a partial index — and a guest can no longer move between weddings once it has
meal counts. Guests do not move weddings.

## D — `guest.lifecycle` is removed, and returns with the RSVP links

**The call.** The column is dropped from `V1`. `expected_attending` and
`expected_party_size` stay `NOT NULL`.

**What it was for.** `2026-08-03-design-domain-model.md` §4: when a response
arrives from someone nobody predicted, we create a **real `Guest`** with
expected slots empty and confirmed slots filled from the response — that
combination *is* "unpredicted person who answered". `PROVISIONAL` carried the
couple's not-yet-given acknowledgement, flipping to `ACTIVE` when they accept.
The empty expected slots are what make merging **lossless**: a provisional
holds no couple-authored data, so discarding it destroys nothing.

**Why it is dead in v1.** Its trigger is a write that happens **while nobody is
watching** — which is precisely the condition the response model was dropped on
(2026-08-06) and which v1 does not have. No RSVP links ship, and the two intake
paths that do (vendor email, CSV import) resolve on a screen the couple is
already looking at. The guest is acknowledged at the moment it is created;
there is no window in which an unacknowledged guest exists.

**And it had become self-contradictory.** With `expected_*` `NOT NULL`, the
same file both declared `PROVISIONAL` and made its defining property —
expected slots empty — unrepresentable. A half-built concept is worse than an
absent one, especially in a schema that is typed by hand.

**Consequence.** 불참 is expressed by `confirmed_attending = false`, not by a
party size of zero, so `ck_guest_confirmed_party_size > 0` stays. Note this is
not yet airtight: the schema still admits `confirmed_attending = false`
alongside a non-null `confirmed_party_size`, and only the aggregation makes the
reading single. `#17` settles it.

`2026-08-03-design-domain-model.md` §4 carries a banner; the definition itself
is untouched and is what the column comes back from.

## One thing the schema decided that no record had

`app_user` carries **no `deleted_at`**. The 2026-08-10 soft-delete table lists
`guest`, `membership`, meal type and `wedding`, and does not mention `app_user`
either way — so the schema was left to decide it, which is the wrong way round.

Stated here so it is a decision rather than an omission: **`app_user` is not
user-deletable in v1.** 회원 탈퇴 is not a v1 requirement, and there is no
screen or endpoint that removes a person. "Every delete is soft" binds rows a
user can delete; a row no user can delete needs no column. When 탈퇴 arrives it
brings the column, and it brings the harder question with it — what happens to
the weddings that person is the only member of.

