# Decision — what a cross-tenant request returns (2026-08-10)

Settles a contradiction between two dated records that was found while
building #4 (the global error handler). Both reviewers flagged it
independently, from opposite directions — one as a spec that stated both
answers, one as a tenant-isolation finding.

- `notes/2026-07-30-decision-network-security.md` deferred list:
  *"Tenant isolation enforced structurally (wedding-scoped repository access,
  **cross-tenant tests returning 404 not 403**) — arguably the highest-value
  item overall."*
- `notes/2026-08-07-decision-backend-api-conventions.md` status table:
  *"Authenticated, wrong wedding/membership | **403**"*

Written before any wedding-scoped endpoint exists, so nothing has to be
unwound. This is the cheap moment — #5 and every endpoint after it inherit
whatever the table says.

## The call

**404 is the default. 403 is a narrow exception with no use in v1.**

The security record wins. Specifically:

- **Any resource addressed by a caller-supplied id, where the caller is not a
  member of the owning wedding, returns 404** — the same response as an id
  that does not exist at all.
- **403 means: the caller *is* a member of that wedding, and lacks a privilege
  within it.** v1 has no roles, so **403 has no correct use today.** That
  sentence is in the record on purpose — 403 is what HTTP instinct reaches for,
  and the instinct is wrong here.

`notes/2026-08-07-decision-backend-api-conventions.md`'s status table is
superseded on that one row. Everything else in it stands.

## Why

**403 on an existing wedding and 404 on a nonexistent one is an oracle.** The
two responses differ, so anyone holding a session can walk the id space and
learn which weddings exist and roughly how many there are — without ever
reading a row. The data stays protected and the existence of the data does
not, which is the distinction the 07-30 record was drawing when it said
*structurally*.

**Making 404 the exception makes it a judgement call, and this codebase
deliberately does not have one there.** The wedding-scoping rule in
`CLAUDE.md` exists to be *"mechanically checkable instead of a per-query
judgement"* — every wedding-scoped aggregate root carries `wedding_id` so
that the check is a property of the schema rather than of an author's
attention. A status code that is 404 "where confirming existence would leak"
puts the attention back: each endpoint has to notice it is one of those
cases, and one endpoint that does not notice re-opens the oracle for all of
them. A default cannot be forgotten.

**The cost is real and small.** 404 is less honest to the developer reading a
log — a membership failure and a typo'd id look identical from outside. That
is the point, and it is why the *server-side* signal has to carry what the
response does not: the 4xx path is where the 07-30 record's
*"alerting on spikes in 401 / 404 / 429"* gets its input. A cross-tenant
attempt must be distinguishable in the log even though it is invisible in the
response. There is no mechanism for that yet — filed rather than assumed.

## What this does not decide

- **Unauthenticated stays 401.** This note is only about an authenticated
  caller reaching a wedding that is not theirs. No session at all is a
  different question with the standard answer.
- **It does not decide how the wedding is resolved.** #5's
  session → membership → wedding resolution is where the 404 will actually be
  produced; this note only fixes what it produces.
- **No code changes because of this note.** Nothing raises 403 or 404 for
  membership yet. `docs/api-spec.md` carries the contract; the enforcement
  arrives with the first wedding-scoped endpoint, and the cross-tenant test
  the 07-30 record asks for arrives with it.
