# Decision — a failed login returns to the front, carrying a code and no words (2026-08-13)

Prompted by `#109`, which came out of building `#38`. The spec handed a
decision to the frontend that the frontend cannot answer.

## The problem, restated

When consent is denied or `state` does not match, the callback answers
problem+json. That answer is a **browser navigation to the API origin**, so
none of our frontend code runs. The user sees a JSON blob with no way back.

**Denying consent is not an error.** A person changing their mind is a normal
path, and today that person hits a wall.

The spec forbids putting `?error` on a redirect, and the ban has two reasons
worth keeping: a query string lands in server logs and in `Referer`, and a
failure reason the caller can choose is a way to put attacker-written words on
our domain.

## The call

**The callback redirects to the front origin with a closed code in the
fragment, and the frontend owns every word the user reads.**

    /login#e=denied     consent refused — a normal path
    /login#e=failed     everything else: state mismatch, token exchange, provider error

Two codes. That is the whole vocabulary.

- **The fragment, not the query.** A fragment is not sent to the server, does
  not appear in access logs, and is not carried in `Referer`. The ban on
  `?error` was a ban on the query string and on attacker-chosen wording;
  neither objection reaches a closed enum in a fragment.
- **The provider's `error_description` is never forwarded.** Same reason `#37`
  refused to publish it: it is text we did not write, arriving at a URL the
  attacker chose.
- **`denied` returns to the ordinary login screen** with a retry, not to an
  error screen. It is not a failure state and must not look like one.
- The Korean copy lives in `web/` as a constant per code.

## Why not have the API render HTML

The other way out was for the API to answer a minimal human-readable page.
Rejected: this repo is JSON only, and the exception would put user-facing
Korean copy in `api/` — a second home for product copy, in the tree that has
no design tokens and no translator. `#63`'s field-level 400 has the same shape
and stays JSON; carving out an HTML path for one route buys a rendering
concern for the whole tree.

## Amended the same day — the environment with no frontend at all

Implementation found a case this record did not answer: **what a failed
callback does when `donghaeng.frontend.base-url` is blank.** That is prod
today, until `#96` names the domain.

- **The problem+json answer survives as the fallback for exactly that case**,
  and `OAUTH_LOGIN_DENIED` / `OAUTH_LOGIN_FAILED` stay in the spec narrowed to
  it rather than removed. Refusing at startup instead would turn an anonymous,
  unauthenticated GET into a 500-with-stack-trace generator — the log
  amplification `unknownProviderIsNotFound` exists to close.
- **But a configured OAuth registration with nowhere to land is a
  misconfiguration, and it refuses at startup.** If a Google
  `ClientRegistration` is configured, `base-url` must be non-blank. Without
  this the two paths answer the same missing config differently — failure gets
  a tidy 401 document, success gets a masked 500 — and half a hole is worse
  than none, because it reads as closed.

## What this does not decide

- **The route and the copy.** `/login` is the obvious landing place, but the
  screen belongs to `#38`.
- **Whether the API is reachable from the browser at all.** If the API ends up
  behind an edge pass-through (`#96`), this redirect becomes same-origin and
  gets simpler. Nothing here depends on which way that goes.
- **Success.** The success path already sets the cookie and redirects; this
  record only touches failure.

Refs `#109`, `#38`, `#37`, `#62`
