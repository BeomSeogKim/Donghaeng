# Decision — session lifetimes: idle 30 days, absolute 180 (2026-08-12)

The founder's call, made while `#37` was in review. It replaces the 14/90 the
implementation had been built with, and it exists because `reviewer` found a test
named `the configured lifetimes are the ones the founder decided` asserting a
provenance that did not exist — the numbers had been chosen by an implementor and
then pinned by a test that claimed otherwise.

`2026-07-30-decision-network-security.md` gives the invite token an explicit
single use and 72 hours, and says of the session only "both idle and absolute
expiry". This fills that gap.

## The call

    idle       30 days   — since the last request
    absolute  180 days   — since the login

## Why, and it is the usage pattern rather than a security argument

**A wedding is planned over about a year, and the couple opens this a few times a
month.** That is the whole reasoning, and it is what a later reader needs, because
the numbers are otherwise arbitrary-looking.

At **14 days idle**, a couple who check the ledger monthly are signed out on
*every single visit*. Not occasionally — every time, by construction. Login is an
OAuth round trip through a provider's consent screen, so that is real friction
levied on the product's most ordinary rhythm, and it buys nothing: a cookie
stolen on day 3 was going to be used on day 3.

At **90 days absolute**, the same couple re-authenticate three times across a
planning window they experience as one continuous task.

30/180 keeps a monthly user signed in while still bounding a stolen cookie to
something a person can be told: half a year at the outside, a month of silence at
the inside.

## What did not change, and is the reason a long idle window is affordable

The session is **revocable server-side** — it is an opaque token against a row,
not a JWT (`2026-07-30-decision-tech-stack.md`). A long lifetime on a revocable
credential is a different proposition from a long lifetime on a self-contained
one: the row can be marked and the token dies on the next request. That is what
makes 180 days a product decision rather than a security concession, and it is
why the JWT rejection keeps paying.

**Logout ships in the same stop** (`#90`), which matters more at these numbers
than it did at the old ones: the couple share one ledger and use each other's
phones and their parents' devices, and a login whose only exit is a 180-day timer
fails 깔끔하되 핵심은 다 있게. Logout ends the session **on the device in your
hand**; signing out everywhere is a separate feature and a separate issue.

## The knock-on nobody would look for

`SessionService` does not rewrite `last_seen_at` on every request — it writes when
the stamp is stale by more than `idle / 24`, so that an authenticated read is not
also a row lock. That divisor is unchanged, so the window it produces moved with
the idle value: **30 hours**, making the effective idle window **28.75–30 days**
rather than exactly 30.

Nothing expires *later* than the number above, which is the direction that would
matter. `docs/api-spec.md` publishes the range rather than the round number, so
the frontend does not build a countdown from a value the server does not promise.

## What this does not decide

- **Not the other tokens.** The invite token keeps its single use and its own,
  much shorter life — 72 hours when this was written, one day since 2026-08-22
  (`2026-08-22-decision-the-invite-link.md`); the link tokens choose for
  themselves when they return.
- **Not JSESSIONID's own timeout**, which is `#98`. That cookie holds the OAuth
  authorization request for the length of a round trip and has nothing to do with
  these numbers.
