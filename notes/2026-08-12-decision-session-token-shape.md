# Decision — the session token has two halves (2026-08-12)

Prompted by building `#37` and needing "constant-time compared"
(`2026-07-30-decision-network-security.md`, Tokens) to be a claim something can
check. Recorded here rather than left in the migration header, because a SQL
comment is where a decision goes to be found by accident.

## The call

The session token is **`<selector>.<verifier>`**, and `user_session` stores the
selector in the clear beside a SHA-256 of the verifier. The row is found by
selector; the verifier is then compared, in constant time, by the application.

    selector       16 CSPRNG bytes, base64url — a public lookup handle
    verifier       32 CSPRNG bytes, base64url — never stored, in any form
    verifier_hash  SHA-256 of the verifier, hex

## Why not the shorter design

The obvious one is a single opaque token with `sha256(token)` under a unique
index, found with `where token_hash = ?`. It is shorter, it is the common
pattern, and it is not insecure.

**The first version of this record claimed it was.** It said that design would
make "constant-time compared" a sentence with nothing behind it, implying a hole.
Two reviewers rejected that independently and they were right, so the corrected
argument is the one that stands:

What the single-token design compares is `sha256(presented)` against
`sha256(stored)`. A variable-time comparison there leaks how many leading **hash**
bytes matched. Turning a hash prefix into a preimage of a 256-bit random value is
not an attack anyone can mount — so the shorter design is fine, and a `timing
attack` framing of it would be theatre.

**What the split buys is not a closed hole. It is a testable one.** In the
single-token design the only comparison happens inside a btree: we do not write
it, cannot time it, and — decisively for this codebase — **cannot watch it fail.**
Removing it is not a thing one can do, so no test can be red for its absence.

In the split design the comparison is ours. `SessionResolutionTest` presents a
real selector with a wrong verifier and expects 401, and deleting
`SessionToken.matches` turns that test red. That is the whole and sufficient
reason: this repo has spent four stops learning that a check nobody has watched
fail is not a check (`2026-08-10-decision-auth-gate-and-sequence.md` lists three
of them; `#82`'s missing lookup assertion in this same stop is the fourth).

**Do not read this as a licence to add columns so that rules have somewhere to be
observed.** The test is worth one column here because the thing being observed is
an authentication decision on every request. The general form of that reasoning
produces schemas designed for their test suites, which is worse than an untested
rule.

## What is also true, and is not the reason

Both designs store nothing replayable: a leaked table yields no working session.
That property is why the shorter design was never unsafe, and it is unchanged
here — the selector carries no authority and the verifier is a hash.

## What this does not decide

- **Not a general token shape.** The invite token, and the link tokens when they
  return, choose for themselves. The link tokens in particular travel in URLs and
  are forwarded by design, which is a different problem.
- **Not the lifetimes.** Idle and absolute expiry are `#37`'s configuration and
  the founder's call.
