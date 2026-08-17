# Decision — the shape the other fifteen endpoints copy (2026-08-17)

`#123` (`POST /weddings`) is the first domain endpoint in the product;
everything before it was auth and infrastructure. What it settles is not the
endpoint — it is the pattern fifteen more will copy, which is why it gets a
record and `#124` will not.

## Where the `CurrentWedding` resolver lives

`#5` resolves `user → membership → wedding` on every wedding-scoped request.
The obvious home is beside `AuthenticatedUser` in `auth/session/`, and it is
the wrong one: `MembershipRepository` is `internal` to `wedding/`, and
`ArchitectureTest` refuses that dependency twice over — cross-domain
persistence, and CONTROLLER reaching PERSISTENCE at all.

**The resolver lives in `wedding/`, and `auth/session/` stops at
`AuthenticatedUser`.** `auth/` answers *who is asking*; `wedding/` answers
*which wedding*, and the walk from a user id to a wedding is a wedding
question that merely starts from a user.

**It reads through a service, never `MembershipRepository` directly.** A
`HandlerMethodArgumentResolver` is an entry point, so it is in the CONTROLLER
layer, and PERSISTENCE may only be reached from SERVICE. Resolver → service →
repository lands with no test edits; a resolver reaching for the repository
goes red on arrival. That is enforcement, not advice, and it is why this is
written down rather than left to be discovered.

## What a domain package looks like

- **One flat package per domain**, no inner packages until something actually
  splits. `auth/` has three because it has three clusters; `wedding/` has one
  thing.
- **A foreign key is a `Long`, never a mapped association.** `createdBy` and
  `membership.user_id` point at `auth/`'s rows, and an `@ManyToOne AppUser`
  would be `wedding/` depending on another domain's entity — refused by test,
  and `user_session.user_id` already set the precedent. The cost is real and
  deliberate: the first endpoint that wants the creator's name (`#9`, the
  partner list) must get a **declared read contract out of `auth/`**, not an
  association. Paying it at fifteen endpoints is the reason for the rule.
- **`XxxRequest` is verb-first for the operation, `XxxResponse` is
  resource-named**, so one `WeddingResponse` also serves the later `GET`. The
  mapping extension lives in the response's own file.
- **The Service owns `@Transactional` and returns the DTO.** The Controller is
  three lines.
- **The authenticated caller is the first handler parameter**, so an anonymous
  request with a bad body answers 401 rather than a 400 that tells a stranger
  which fields exist. Argument resolution is declaration order, which is too
  quiet a thing to rest on — **so it is swept**: every handler taking an
  `AuthenticatedUser` takes it at index 0. Keyed on the *type*, not the
  annotation, because a rule keyed on the annotation misses precisely the
  handler that forgot it.
- **Entity timestamps carry no defaults; the service is the only clock.** Two
  sources of "now" means the entity default is dead on the production path and
  alive only in tests. `AppUser` in `auth/` still has defaults and now
  disagrees with this — worth aligning when something else touches it.

## Validation belongs in the app, and its bound is the column's

The standing rule from `2026-08-17-decision-log-masking-mechanism.md` — **a
cast is not a validator** — is what this endpoint is the first to apply, and
the first draft applied it to the names and not to the date. `LocalDate`'s
range is wider than PostgreSQL `date`'s and Jackson accepts expanded years, so
`+999999999-12-31` was a masked 500.

**`@StorableDate` bounds the date at the column's limits, not at anything a
wedding plausibly is.** The upper boundary is asserted as *accepted*, which is
what stops a storage constraint from silently becoming an unrecorded product
rule. **Whether a wedding date needs a product bound is undecided and is the
founder's** — a typo'd year is one keystroke and nothing downstream catches
it, but there is no venue contract behind such a bound the way there is behind
보증인원.

**A past date is accepted.** Building a ledger after the fact is plausible, and
the costs are asymmetric: a wrong date is an edit in 설정, a wrong refusal is a
couple who cannot start.

**Length is measured on the value as sent, before the trim.** Trimming first
would need a custom deserialiser on every string field of fifteen DTOs — a
rule that fails *silently* when forgotten — bought to fix a case that bites
only at exactly 101 characters whose last is a space. `web/` trims client-side
and the spec says so.

## What this stop does not decide

- **A product bound on the wedding date** — founder's, above.
- **Whether an unknown JSON member is refused or ignored.** Today
  `guaranteedHeadcount` can be sent, answered 201, and stored nowhere. That is
  an API-wide policy, not this endpoint's, and it has its own issue.
- **`#5` itself** — only where it may live.

Refs `#123`, `#7`, `#5`, `#8`, `#9`, `#63`, `#66`,
`2026-08-10-decision-auth-gate-and-sequence.md`,
`2026-08-07-decision-backend-architecture.md`
