# Decision — two session cookies: refuse the read, revoke both (2026-08-12)

Written after the `#37` logout audit found that this rule existed **only as a
KDoc on one function**. Nothing in `notes/` or `api/AGENTS.md` mentioned it, so
nothing bound the *next* code path that read a token — and the next code path was
logout, in the same stop, where obeying half the rule produced the takeover the
other half exists to prevent.

## The threat

Our session cookie has no `Domain` attribute, so it is host-only. **A sibling host
under the same registrable domain can set a cookie with the same name and a
`Domain` that covers us**, and the browser will then send both. The server cannot
tell which host set which, and the RFC fixes no order.

The deployment shape invites exactly this: `web/` on Cloudflare Pages and this API
on a VPS, under one registrable domain (`2026-07-30-decision-tech-stack.md`).

## The call, and it is different for the two operations

**Reading a token is strict. Revoking one is greedy.**

- **Resolution** (`SessionTokens.of`) returns `null` when more than one is
  present. Acting on the wrong one of two tokens means acting as the wrong
  person, silently, with every write landing in a stranger's ledger.
- **Revocation** (`SessionTokens.all`) takes every well-formed token the request
  carries. Each still has to pass the constant-time verifier check, so this
  authorises nothing; it only ends things.

**Do not "make them consistent."** That is the whole content of this record.

## Why greedy revocation is not optional

The audit reconstructed the chain, and it turns the documented denial of service
into session fixation:

1. A sibling plants a cookie holding the **attacker's own valid token**.
2. Every request carries two, resolution refuses, the couple see a broken app.
3. They press sign-out — or the frontend calls logout in response to the 401.
4. Logout, reading the single unambiguous token, finds none and revokes
   **nothing**. It then clears the cookie — and a `Set-Cookie` without a `Domain`
   can only delete the host-only one. **Ours.**
5. The planted cookie is now alone in the jar, and still valid. The next request
   resolves cleanly, **as the attacker.**

So the sign-out gesture completed the takeover. Secondarily, the couple's own row
was never revoked and would have lived out its full 180 days
(`2026-08-12-decision-session-lifetimes.md`).

Greedy revocation kills the planted token in step 4. The cookie may survive in the
jar — we cannot delete what we did not set — but it is worth nothing.

## What refusing the read actually costs

Say it plainly, because it is easy to overstate: **not "a re-login".** The planted
cookie persists, so every later request still carries two and still refuses,
including the one right after logging in again. The session is unusable until the
person clears cookies or logs out (which now at least ends both tokens).

That is a denial of service, and it is the right trade against sitting silently
inside a stranger's ledger. It is not a comfortable outcome, and the discomfort is
why the stronger answer below is filed rather than dismissed.

## The stronger answer we did not take

The **`__Host-` cookie prefix**: a browser refuses to let any host set such a
cookie with a `Domain` attribute at all, so the ambiguity cannot be created. It
requires `Secure`, and dev serves `http://localhost`, so it cannot be the
mechanism in every environment — and a control that is absent in dev is one
nobody exercises. Filed rather than half-adopted.

## What this binds

Every future path that reads a session token, which is the reason this is a record
and not a comment. Tokens come from `SessionTokens` and from nowhere else; a path
that reaches into `request.cookies` itself has silently opted out of both halves.
