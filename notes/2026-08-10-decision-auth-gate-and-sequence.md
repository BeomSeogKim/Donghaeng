# Decision — where the auth gate lives, and the order auth lands in (2026-08-10)

Prompted by the founder asking whether bringing security in ahead of the core
features would block them. The answer reorders the last substrate item,
splits `#5`, and shrinks `#62` to almost nothing.

## The call

**1. The resolver is the gate, not the filter chain.** `user → membership →
wedding` resolution is what rejects a request. Spring Security's
`authorizeHttpRequests` stays permissive. This is a stated design with a test
behind it, not deferred hardening.

**2. Auth lands after login, not before it.**

    #3       baseline schema
    #6/#37   social login — session issuance + CurrentUser
    #7       웨딩 만들기 — the first membership exists here
    #5       CurrentWedding resolution + cross-tenant 404
    #8~      vertical, all of it

**3. `#5` splits along the user/wedding axis.** `token → session → user`
folds into `#6` — the thing that issues a session is login, and the cookie,
hashing, expiry and re-issue rules `#5` recorded travel with it unchanged.
`#5` keeps `user → membership → wedding` and lands after `#7`, because before
`#7` there is no membership to resolve. It is retitled to say so.

## Why the resolver and not the filter chain

**No v1 requirement can be built ahead of auth.** Walking `#7`–`#24`, every
one sits behind "who is asking" and "which wedding" — 하객 추가 needs a
wedding, the wedding needs a creator, the creator comes from a session. Even
`#7`, the one endpoint not scoped to a wedding, needs a `userId` to know whose
wedding it is. So the real fork was never "security first or features first";
it was "a real session or a hardcoded `userId`". The hardcoded one threads
through fifteen endpoints and their tests before it is pulled out, and the
commit that pulls it out is exactly where a cross-wedding leak enters.

**The two gates are not equally expensive to retrofit, and that asymmetry is
the whole decision.**

- The **filter chain** is one line. Flipping it later turns every
  fixture-less test red at once — it announces itself, loudly, in CI.
- The **resolver** is the shape of the controller signature. Retrofitting it
  means threading a parameter through every endpoint written in the meantime,
  by hand, silently.

The one that must be present from the first endpoint is therefore the
resolver. The filter chain is defense in depth, and defense in depth is what
you are allowed to add late.

## Why permissive is a design here and not debt

"Temporarily permissive" has no forcing function. Nothing goes red to say
*tighten now*, so the tightening is remembered or it is not. This codebase has
spent three consecutive stops learning the same lesson — a check nobody has
watched fail is not a check:

- Flyway creates `flyway_schema_history` with **zero** migrations, so the boot
  test asserting the table exists stays green while "the migration files are
  the only copy" quietly becomes false (`#3`).
- `SchemaOwnershipGuard` could disable itself and nothing observed that it was
  still registered in the shipped jar (`#60`).
- `@RestControllerAdvice` never sees an exception thrown in a filter, and the
  error contract's guarantee had nothing measuring it (`#4`).

A promise to tighten later is the same species. So it is replaced with
something observable: **`#5` asserts that an anonymous request to a
wedding-scoped endpoint gets 401, and that an authenticated non-member gets
404.** With those two tests, `permitAll` at the filter level is a choice under
observation rather than an unpaid debt, and it stays permissive in every
environment — including production. That is what calling it a design, rather
than a dev convenience, commits us to.

### The one thing that must not be left open

The failure mode this trades for is **an endpoint that forgets to take
`CurrentWedding`** — under `authenticated()` it would still demand a session,
under `permitAll` it is open to anonymous callers.

So `#5` carries a hard acceptance criterion: **forgetting the resolver must
fail closed, not open.** Either a `HandlerInterceptor` that denies any handler
which has not declared itself public, or a build-time test that every handler
method takes a resolved principal — the mechanism is `#5`'s to pick against
real code, but shipping neither is not an option. A default cannot be
forgotten; a convention can.

## What this does to `#62`

`#62` was filed as "`#5`'s precondition": `GlobalErrorHandler`'s catch-all
swallows `AccessDeniedException`, and filter-level rejections bypass the
advice entirely. Both halves shrink.

**The 403 half was already dead when it was written.** The same-day
cross-tenant decision (`2026-08-10-decision-cross-tenant-status-code.md`) made
a tenancy failure a 404 produced by a service lookup finding nothing — not an
`AccessDeniedException` from `@PreAuthorize`. v1 has no roles, so there is no
correct 403, and therefore no `@EnableMethodSecurity` and no
`AccessDeniedHandler` to write.

**The 401 half moves inside our own contract.** If the resolver rejects with a
`DomainException`, the advice `#4` already built answers it, in problem+json,
with a `code`. Spring Security rejects nothing, so nothing bypasses.

What survives is a different thing from what the issue says: **OAuth callback
failure** — consent denied, `state` mismatch — is thrown by the OAuth2 login
filter and does bypass the advice. One concrete case, owned by `#6`. It is
also the moment the Tomcat error-page hardening built earlier today stops
being anticipatory: Spring Security's chain registers for the `ERROR` dispatch
by default, so `#6` is what finally puts real code on that path.

## CSRF

The session is cookie-borne. **Stack** already fixes that — server-side
session behind an HttpOnly cookie — and the standing client rule ("session
lookup reads a token from the request rather than a cookie") is about the
lookup code path staying transport-agnostic, not about the transport. So CSRF
is live from the moment the cookie exists, independent of how permissive the
authorization rules are.

**v1's mitigation is `SameSite=Lax` plus no state-changing GET.** Lax
withholds the cookie on cross-site POST and admits it only on top-level GET
navigation, so the pair closes the hole — and the second half has to be
written down, because it is what makes the first half true. A CSRF token is
defense in depth and belongs to `#48`, the pre-deploy security decision.

Two consequences worth stating so neither reads as an oversight later:

- **Spring Security's CSRF filter is on by default, and turning it off is an
  explicit act with a stated substitute** — the Lax pair above. Not a silent
  `csrf { disable() }` next to the permissive rules.
- **`SameSite=Strict` remains the trap `#5` recorded**, and it travels to `#6`
  with the cookie: the OAuth callback is a top-level cross-site navigation, so
  Strict drops the cookie at exactly the moment of login.

## What this does not decide

- **It softens no wedding-scoping rule.** Every wedding-scoped aggregate root
  still carries `wedding_id`; a cross-tenant request is still 404.
- **It does not touch the session's storage shape.** ≥128-bit CSPRNG,
  SHA-256-hashed storage, constant-time comparison, log masking, idle *and*
  absolute expiry, re-issue on login — all of `#5`'s recorded requirements
  stand. Only their *timing* changed, from before login to with it. Spring
  Session JDBC storing session ids in plaintext is still the trap to avoid.
- **It does not decide how 네이버 · 카카오 are registered.** Google is a
  standard provider; the other two need hand-written `ClientRegistration`s and
  user-info mapping. That is `#6` implementation, not policy.
- **It does not license a masked 500 on the 4xx path.** `#63`–`#67` are
  unaffected — in particular `#65` (no log source for a 401/404 spike) gets
  *more* load-bearing here, since the cross-tenant 404 is deliberately
  invisible in the response.
