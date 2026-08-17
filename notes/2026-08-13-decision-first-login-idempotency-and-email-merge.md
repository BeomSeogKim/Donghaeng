# Decision — first login is idempotent, and only a verified address merges (2026-08-13)

Settles `#93` and `#94`, and folds `#101` into `#89`. All three came out of
the `#37` audit and all three are really one question: **when is a person
arriving for the second time the same person?**

## `#93` — first login is idempotent by construction

Two first logins for the same identity arriving at once both take the creation
branch; one dies on the unique index and surfaces as a masked 500. It is rare,
it looks like "login fails sometimes", and it never gets reported.

**The call: the create path treats a unique violation as "already exists" and
continues as a login.** First attempt registers, second attempt signs in — and
the second attempt is the same code path either way.

**The lock is the identity's own index row and nothing wider.** No table lock,
no application-level mutex, no serialization of unrelated logins. Two different
people signing in at the same instant never meet. That constraint is the point:
a global guard would answer `#93` and create a worse problem at the moment the
product is busiest.

### What building it added to this section (2026-08-17)

**The retry on `ux_app_user_email` is a merge, and that is only safe while the
column cannot hold an unverified address.** Catching that index and resolving
again turns "someone claimed an address the index refused" into "seat them on
the row that already holds it". Today the row is provably provider-verified —
a biconditional CHECK, a verifier allowlist, and a merge key that is null
unless the provider said verified. **So `#94`'s self-verification step must
write the address only *after* confirmation, never before.** Writing it first
would convert this recovery into the takeover `#94` §2 exists to prevent,
reached from the other direction. This dependency runs the opposite way to the
issue order, which is why it is written here rather than left to be noticed.

**Two collisions in a row are reachable, so the retry is bounded at three
passes, not two.** A login can lose on the email index and then on the identity
index — different axes, which is what makes "it can only lose the same way
twice" wrong. Three is provable: an identity loss is always the last loss, and
an email loss cannot repeat, because both losing rows are committed and neither
table is user-deletable.

**Login is now a chain of independent commits, not one transaction.** The
registration attempt runs `REQUIRES_NEW` in its own bean — a rollback-only
transaction cannot be caught and re-read on — and `login()` itself is no longer
transactional, because the retry must see rows another transaction committed
after this login began. A crash between account creation and session issuance
leaves an account with no session, which the next login adopts. **`#94`'s
profile write is therefore a third independent commit**, not part of a login
transaction.

## `#94` — the merge key must be verified, and we can become the verifier

`#94` has two halves and only the second one matters much. Profile refresh
(a changed Google display name) is a write on login. The real problem is that
**a user created without a verified email never gets one**, so someone who
signs up with Kakao and later signs in with Google stands as two accounts.

**The call:**

1. **If the provider gives us no verified address, we ask for one in a
   separate step** — after login, not as a gate on it.
2. **A typed address merges nothing until we verify it ourselves.** We mail a
   code; on confirmation the row stands as verified by us and only then serves
   as a merge key.
3. **When a verified address collides, the social logins fold into one
   account.** That is the founder's call and it is the right one — one person,
   one ledger.

Step 2 is not ceremony. **Merging on an unverified, user-typed address is
account takeover**: sign in with any provider, type the victim's Google
address, and land inside their ledger — which holds guest phone numbers today
and 축의금 later. The schema already refuses to hold an address whose verifier
is unknown (`ck_app_user_email_verifier_known`); this decision adds ourselves
to the set of verifiers it knows, rather than carving an exception around it.

**This buys new scope: outbound email.** v1's stack has no mail path — no
provider, no templates, no bounce handling, no rate limit on resends. It gets
its own issue and sits after `#89`, because until a second provider exists
nobody can arrive without an address in the first place.

## `#101` — folded into `#89`

`#101` asks for a test asserting every `ClientRegistration` carries `openid`
in its scope, with 네이버 as an explicit exception. That is not a decision;
it is the guard that makes `#89`'s sharpest trap loud. Without it, Kakao
registered without `openid` issues no ID token at all, and ID-token validation
does not fail — **it silently has nothing to validate**, with no test turning
red. The exception for 네이버 being written down in the assertion is itself the
value, because 네이버 is plain OAuth 2.0 and never had an ID token to check.

It ships inside `#89`.

## What this does not decide

- **How the extra-email step looks** — the screen belongs to the issue that
  builds it.
- **The mail provider** — the new issue's problem.
- **What happens to the losing account's data on a merge.** In v1 an account
  with no wedding has nothing to move, and the couple's shared ledger is
  reached through the Wedding, not the user. Revisit before a merge can happen
  to someone who already owns rows.

Refs `#93`, `#94`, `#101`, `#89`, `#37`, `2026-08-11-decision-baseline-schema-calls.md`
