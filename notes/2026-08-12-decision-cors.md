# Decision — CORS: deny by default, exact origins, no patterns (2026-08-12)

Recorded when `#97` was implemented in `#37`'s stop. The policy itself was
settled earlier, in the `#2` security audit that produced `#6`'s comment — but an
issue never decides anything (2026-08-08), and a security control whose only
statement lives in an issue comment has no record. This is that record; it adds
no policy the audit did not already set.

## The call

**The browser may call this API from an origin the server has listed, and from
nowhere else.**

- **The base allows nothing.** An environment that has declared no frontend is
  not reachable from any page. `dev` allows exactly `http://localhost:3000`.
- **Exact origin strings.** Scheme, host and port, compared as strings.
- **`allowedOriginPatterns` is never used** — not "used carefully".
- **Credentials are allowed**, because the session rides a cookie.
- Methods `GET, POST, PATCH, PUT, DELETE`; request headers `Content-Type` and
  `Accept`. A custom header is a backend change.

## Why exact strings and not a pattern

The pattern API exists for `https://<star>.example.com`, and the failure it
invites is the one an attacker registers a domain for: a pattern meant to match
subdomains of `donghaeng.kr`, written slightly wrong, also matches
`donghaeng.kr.evil.com`. Suffix matching on a hostname is the same class of bug
as suffix matching on a path, and it fails **open**.

We have one frontend. A pattern buys nothing here and costs a whole family of
mistakes, so it is refused at the type level rather than reviewed for.

## Why the wildcard is not merely discouraged

`Access-Control-Allow-Origin: *` is **illegal** beside
`Access-Control-Allow-Credentials: true`; a browser rejects the pair. So the
failure mode of writing `*` here is "nothing works", not "everything is exposed".

It is still refused explicitly, because the repair someone reaches for when `*`
stops working is `allowedOriginPatterns`, which does work — and fails open.
Naming both together is what stops that path.

## Why production has no origin

It has no frontend yet. The value is the production web origin and cannot be
guessed: too narrow breaks the site, too wide admits a host we do not control.
It is set together with `donghaeng.frontend.base-url` (`#96`) when the domain is
decided. Until then production denies every cross-origin request, which is the
correct behaviour for an environment with no frontend rather than a gap.

## How it is held

**On the bound properties, not on the files.** The file sweep alone was
insufficient in two independent ways, and both were found by review:

1. **The environment outranks every yml** (2026-08-09). `DONGHAENG_CORS_ALLOWED_ORIGINS`
   in a deploy platform reverses any committed value with the whole suite green —
   the same reason `server.error.*` and `server.tomcat.accesslog.enabled` are
   asserted on resolved properties rather than on files.
2. **A `List<String>` binds from a comma-delimited scalar.**
   `allowed-origins: "https://a,https://b"` produces one un-indexed property, so
   a sweep that inspected `allowed-origins[0]`, `[1]`, … skipped it entirely and
   passed vacuously. Binding is the only form that cannot be spelled two ways.

So `RealConfigurationBootTest` asserts the resolved `CorsProperties` per profile,
and `ProfileConfigurationTest` binds each committed file's properties rather than
reading their keys.

## What this does not decide

- **CSP, `Referrer-Policy` on the frontend, and the `VITE_` variable model** stay
  `#48`. `Referrer-Policy: no-referrer` on the API's own responses is separate
  and already required by the token baseline (2026-07-30).
- **Nothing about authentication.** CORS is a browser-enforced rule about who may
  *read* a response; it is not an access control. The gate remains the resolver.
