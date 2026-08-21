# Decision — refusing the second wedding: the status, the lock, and the list that holds one (2026-08-21)

> **Amended the same day (`#158` follow-up).** §2's recommended partial unique index
> **was applied by hand** — `create unique index ux_membership_user on membership
> (user_id) where deleted_at is null`, REPLACING the non-unique `ix_membership_user`.
> The invariant now lives in the database as well as in the application. What that
> changed is written into §2, §4 and §5 below; the decisions this record made are
> unchanged.

`#158`, the API half of the founder's rule in
[2026-08-21-decision-two-accounts-and-the-v1-recut.md](2026-08-21-decision-two-accounts-and-the-v1-recut.md)
§1: **a person belongs to exactly one wedding — created or joined, never both,
never two.** The rule itself is recorded there and is not re-argued here. This
records the four calls the implementation had to make, each of which a later reader
would otherwise re-derive from scratch.

**What this reverses is a written spec decision**, not an omission.
`docs/api-spec.md` said, in as many words, "A second wedding by the same person
succeeds… Do not treat a 201 here as proof they had none." That paragraph is
inverted, dated, and marked as a reversal in place.

## 1. The refusal is 409 `ALREADY_IN_A_WEDDING`

409 is the obvious reading, and the obvious reading is not automatically right in
this repo: every cross-tenant refusal collapses into one 404 on purpose
([2026-08-10-decision-cross-tenant-status-code.md](2026-08-10-decision-cross-tenant-status-code.md)),
because a status that distinguished "not yours" from "does not exist" is a
wedding-id oracle for anyone holding a session.

**That reasoning does not reach this refusal, and the distinguishing fact is whose
account it is about.** The 404 hides a *third party's* row. This one names no
wedding at all — it says something about **the caller's own account**, which they
already know and which `GET /weddings` hands the same session in full. There is
nothing left to leak. A 404 would hide nothing and would also lie to `web/`: it
means "what you asked for is not available to you", and the truth here is "you
already have one; go and open it".

**The `code` names the caller's state, not this endpoint's outcome** —
`ALREADY_IN_A_WEDDING`, not `WEDDING_ALREADY_CREATED`. `#9`'s invite accept refuses
on the identical fact, and a second word for one state is a second copy to drift.

**The recovery is published, because a bare status is not one**: on 409, call
`GET /weddings` and open what comes back. Never a retry, never an error screen.

## 2. What actually stops two simultaneous creates is a lock, not the check

A read-then-insert in the service is a race with a window, and the window is not
theoretical — it was measured before the fix. Six barrier-synchronised
`POST /weddings` from one session **all returned 201**. Two tabs, or one button
double-tapped on a slow connection, is the same shape.

Four mechanisms were available and only one of them is both correct and free of new
DDL:

- **A partial unique index** on `membership (user_id) where deleted_at is null`
  makes a second live membership *unrepresentable*, which is what this repo reaches
  for elsewhere. It was not in the endpoint change — every DDL statement against a
  real database is applied by hand by the founder
  ([2026-08-09-decision-schema-ownership.md](2026-08-09-decision-schema-ownership.md))
  — and it was **applied later the same day** as `ux_membership_user`, replacing the
  non-unique `ix_membership_user` over the same column and predicate. Two indexes
  over one predicate is write cost buying nothing, so it is a replacement and not an
  addition.
- **`SELECT … FOR UPDATE`** has no row to take. The rule constrains a person with
  *no* membership yet, and you cannot lock what does not exist.
  `ux_membership_wedding_user` is no help either: it is keyed `(wedding_id, user_id)`
  and partial on `deleted_at is null`, so two memberships in two *different* weddings
  never collide in it. That index is about re-inviting a removed partner
  ([2026-08-10-decision-soft-delete.md](2026-08-10-decision-soft-delete.md)) and says
  nothing about "one per user".
- **`SERIALIZABLE`** would detect it and answer `40001`, which then needs a retry
  policy and an error translation for a case that has one correct answer already.
- **`pg_advisory_xact_lock(user_id)`**, which is what shipped. It is the case
  advisory locks exist for — a mutex on a key with no row — and it is released by
  `COMMIT` or `ROLLBACK`, so a failed request cannot leak one and there is nothing to
  unlock by hand.

**The index did not make the lock redundant, and this is the sentence that stops it
being deleted.** They answer different questions. The index decides **what may
exist**: it is the last word, it holds against psql and against a `#9` path that
forgets the check, and it cannot be reasoned around. The lock decides **what the API
answers**: it serialises two simultaneous `POST /weddings` on the user id so that the
second transaction *reads* the first one's committed membership and is refused by the
ordinary check. Without the lock both read nothing, both insert, and the loser's
answer has to be recovered from a rejected INSERT.

That recovery exists and is deliberate — `SoleMembershipCollision` matches the
constraint name off the wire (the reading `IdentityCollision` established for `#93`)
and re-throws `AlreadyInAWeddingException`, so **the loser of a race is told exactly
what someone who simply already had a wedding is told.** From their side it is one
fact with one published recovery, and an untranslated violation would be a masked 500
inviting a retry that can only fail again. But it is a backstop: the lock is what
keeps that path exceptional rather than the normal way a double-tap is handled.

**The key is the user id and it is the application's only advisory lock.** A second
kind must namespace both keys; a collision costs waiting, never a wrong answer.

**`Propagation.MANDATORY` on the check** is the part that is easy to get wrong later.
A lock taken in a transaction of its own is released immediately and guards nothing,
so the check must run inside the transaction that does the insert. `MANDATORY` makes
a caller who forgot fail loudly instead of quietly re-opening the race. It does not
fire for `create`'s own call — self-invocation skips the proxy — which is exactly why
it is written for the caller that will arrive from another bean, i.e. `#9`.

## 3. One check, in one place, because `#9` uses it too

`WeddingService.claimSoleMembership(userId)` is where the rule is decided, and every
path that creates a `membership` row calls it first. `#9`'s invite accept is the
second such path and calls **this**, rather than asking the same question its own
way: two places deciding whether a person already has a wedding is how they drift,
and the second one would arrive without the lock above.

`api/AGENTS.md` carries that obligation as a rule, in one line, because an agent
writing `#9` would otherwise write `existsBy…` again and be right-looking.

## 4. `GET /weddings` stays an array — and the spec now says "at most one"

Narrowing it to a single object would break every call site on the seam and buy
nothing; `web/` generates its types from this shape. The change is not to the shape
but to what the spec **promises**: at most one entry.

That sentence is the point. `web/` reads `[0]`, and before this rule that was a guess
about which of several ledgers the person meant. Now it is correct by contract rather
than by luck.

**"Newest first" is retained but no longer decides anything** — amended when the index
landed. It was retained here for one account: whoever acquired a second wedding before
the refusal existed. **`create unique index` proved there is no such account** — it
would have failed on the duplicate — so no response can carry a second entry for the
order to place. The `order by` stays in `findAllLiveForMember` (sorting one row costs
nothing, and it is the right default if a person may ever hold several weddings
again), the spec says it decides nothing, and the contract test that exercised it was
deleted rather than kept green over a state no database of ours can hold. Amends
[2026-08-20-decision-listing-the-callers-weddings.md](2026-08-20-decision-listing-the-callers-weddings.md)
§1, which argued the array from "a person may belong to several"; the array survives
its premise, on the seam argument alone.

## 5. A cost this pays, stated because it is invisible in the diff

Three tenancy tests built their second wedding by calling `POST /weddings` twice with
one session. Each exists to kill one mutation — **a query scoped to the CALLER
instead of to the WEDDING** ([2026-08-19-decision-wedding-scope-gate.md](2026-08-19-decision-wedding-scope-gate.md)
§2b) — and that mutation becomes *unobservable* the moment no caller can hold two
memberships. Left alone, those tests would have stayed green forever while testing
nothing.

They first inserted the second wedding directly (`ApiFixture.insertSecondWedding`),
on the argument that wedding-scoping must not rest on a rule enforced one layer away
— least of all on one the database does not enforce at all (§2).

**The index settled that, and in doing so took the workaround away too.** A caller
with two live memberships is now a row Postgres refuses, so the fixture could only
have survived by building a state no database of ours can hold. What replaced it is
`ApiFixture.joinAsPartner`: **the same mutation, observed where it is still real.**
While a caller has exactly one wedding, "the caller's rows" and "this wedding's rows"
are the same rows and a caller-scoped query is merely equivalent — but the couple are
two accounts in one ledger (`#9`), and there a caller-scoped query silently drops
everything the partner entered. A 하객 missing from the list, a head missing from
보증인원, and a 200 on both. The three tests now put a partner in the caller's wedding
and a stranger in their own, which kills the caller-scoped mutation and the
unscoped one in one assertion each.

`CreateGuestContractTest`'s member-of-two-weddings test became "the partner writes
into the ledger they were let into, as themselves" — the write target is no longer
distinguishable by caller, but `created_by` is, and that is `#25`'s audit trail.

## What this does not decide

- ~~**Whether the database gets the unique index.**~~ Decided: applied 2026-08-21,
  see the banner and §2.
- **Whether `ux_membership_wedding_user` still earns its write.** It is now implied:
  any live duplicate of `(wedding_id, user_id)` duplicates `user_id`, so
  `ux_membership_user` refuses everything it would have refused, and it can no longer
  fire alone. It is kept because it is the constraint that comes back into force the
  day a person may hold several weddings, and because dropping an index is DDL. Not
  in scope for the follow-up; named so nobody re-derives it.
- **What happens to an account that already holds two.** None is known to exist. The
  refusal is on *creating*, so both of theirs keep working and `[0]` stays stable;
  merging or choosing between them is a screen nobody has designed.
- **`#9`'s own contract.** It reuses the check and the `code`; everything else about
  invites is its own issue.

Refs `#158`, `#9`, `#148`, `#132`,
`2026-08-21-decision-two-accounts-and-the-v1-recut.md`,
`2026-08-20-decision-listing-the-callers-weddings.md`,
`2026-08-19-decision-wedding-scope-gate.md`,
`2026-08-10-decision-cross-tenant-status-code.md`,
`2026-08-10-decision-soft-delete.md`,
`2026-08-09-decision-schema-ownership.md`
