# Decision — every delete is soft, and a deleted guest returning in a file is a question (2026-08-10)

The founder's call, closing the last open half of `#32` and unblocking `#3`.

## The call

**1. Soft delete is the service-wide default.** Nothing a user deletes is
removed from the database; the row stays with a `deleted_at` stamp.

**2. A deleted guest that reappears in an import is warned about, not silently
handled.** The couple is asked whether to restore them.

The question arrived through `#32` — where the import file hash lives — because
the import matcher is the first thing that has to know what a deleted row means.
It turned out to be a service-wide question wearing an import question's
clothes.

For the record: the recommendation on the table was hard delete plus a
`GuestChange` deletion event, on the grounds that `deleted_at IS NULL` is one
more forgettable condition on every query. The founder chose soft. That trade is
now real, so the rest of this note is about **where the weight of it lands and
what holds it** — the mitigations are the decision, not a footnote to it.

## The cost, and where it actually bites

### Native aggregation queries get no automatic filter

Hibernate's `@SQLRestriction` on the entity appends `deleted_at IS NULL` to the
JPA path. **It does not touch native queries.** And this project's aggregation
is native by standing constraint — which means the one path the automatic filter
cannot reach is the path that computes the meal guarantee.

A missed filter there does not throw. It returns a number that is too high, in
the direction of ordering meals for people who are not coming, which is money.
This is the same shape as the wedding-scoping rule and gets the same treatment:
it must be mechanically checkable rather than remembered.

**And there is a genuine conflict inside it.** `@SQLRestriction` hides deleted
rows *globally* — but the import matcher has to **see** them, or it cannot ask
the question decision 2 requires. The mechanism that protects every other query
is the mechanism that breaks the one feature this decision exists for.

**Resolution: `@SQLRestriction` as the default, with one explicitly named path
for the matcher that bypasses it.** The reasoning is failure direction — with
the default on, forgetting shows *fewer* rows, never leaks deleted ones. What
remains at risk is the native set, which is small and enumerable, so it is
closed by a test rather than by attention. Filed on `#17`.

### Unique constraints must become partial indexes

A soft-deleted row keeps occupying its unique slot. Remove a partner and
re-invite them and the dead membership blocks the new one:

    -- blocks re-invite
    UNIQUE (wedding_id, user_id)

    -- correct
    CREATE UNIQUE INDEX ... ON membership (wedding_id, user_id)
      WHERE deleted_at IS NULL;

Applies wherever `#3` writes a unique constraint. Guests are unaffected — there
is no unique constraint on a name, because two guests legitimately share one.

### `deleted_at` goes on user-deletable rows only

"Every delete is soft" is not "every table gets the column". Append-only records
do not get it, and saying so is what keeps the audit log an audit log.

| table | `deleted_at` | why |
|---|---|---|
| `guest` | yes | the subject of this decision |
| `membership` | yes | removing a partner — needs the partial index above |
| meal type | yes | though a type in use cannot be deleted at all (2026-08-06) |
| `wedding` | yes | for whenever a withdrawal path exists |
| `guest_change` | **no** | append-only; a deletable audit log is not an audit log |
| import / `email_ingest` | **no** | records of events that happened; deleting one breaks hash idempotency |

## The third question on the import review screen

The screen had two questions. It now has three:

| situation | the two buttons |
|---|---|
| same person, 인원수 differs | 내 값 유지 / 파일 값으로 |
| identity unclear | 합치기 / 따로 두기 |
| **a deleted guest is in the file** | **되살리기 / 그대로 두기** |

**"그대로 두기" must be remembered.** The parents' file will keep listing that
person on every future send, so an unremembered answer turns into a permanent
nag. This is not a new mechanism — the standing rule *"a resolved question is
not asked again"* already covers it, and this is a third kind of question
flowing through it.

### This is a deliberate exception to the stale-name-list rule

`2026-08-07-decision-import-idempotency.md` established that **a returning file
is a stale name list, not a fresh assertion** — which is exactly why import
never touches an existing guest's attendance. Applied literally, a deleted guest
appearing in a returning file is also just a row nobody removed, and should be
skipped in silence.

We ask anyway, and the exception is intentional:

- **Attendance has a screen; deletion does not.** A wrong 참석 is one tap away
  from being fixed in the ledger. A wrongly deleted guest is invisible there —
  the import is the only place the couple will ever be told they exist.
- **The cost is bounded by the rule above.** One question per person, once, ever
  — not once per import.

Recorded as an exception rather than a revision: the stale-name-list reasoning
stands everywhere else, and attendance in particular is untouched.

## What this does not decide

- **It does not add a restore screen.** Restoring happens through the import
  question, and through nothing else in v1. A general "휴지통" is not in scope
  and is not implied by this note.
- **It does not decide retention.** How long a soft-deleted row lives is `#34`
  (`GuestChange` retention) territory and is still open.
- **It does not change what deletion means to the couple.** The guest disappears
  from the ledger and from every count. Soft is an implementation property, not
  a user-visible state.
