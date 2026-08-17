# Decision — the front ships static, and the CSRF gate is a content-type ban (2026-08-13)

Closes `#99`. Prompted by asking what "hide the backend" would actually cost,
and finding that the answer changes less than it looks.

## Part 1 — the front deploys as static files, and that is enough for v1

`web/` goes to Cloudflare Pages as built assets. No server, no SSR, no
rendering runtime.

This is not a compromise: **v1 has no guest-facing surface.** RSVP links are
deferred, so every screen sits behind login, and nothing needs SEO, share
previews, or server-rendered HTML. The things that sound like they need a
server do not — CSP and `Referrer-Policy` (`#48`) ship as a `_headers` file,
SPA routing falls back via `_redirects`, and `VITE_` values are public by
construction because the frontend holds no secret.

**Hiding the API behind the front is deferred to `#96`,** where the prod
domain gets named. Two things make that safe to defer:

- **It is not a feature requirement.** Nothing in v1 needs it. It buys reduced
  attack surface and costs an edge pass-through plus a mechanism that makes
  "only the front reaches the API" true — a tunnel, or a shared secret between
  edge and origin. A proxy alone leaves the API public.
- **It does not touch `web/` code.** The frontend calls `/api/*` either way;
  only the deploy config decides who answers. The migration cost if we do it
  later is re-registering the OAuth callback URL.

Worth knowing when we get there: **hiding the API requires the edge layer, not
merely permits it** — the OAuth callback is a browser navigation, so if the
browser cannot reach the API, the callback URL must be on the front origin and
be forwarded.

And worth knowing for post-v1: **public RSVP links will want a KakaoTalk share
preview**, whose OG tags must be in the initial HTML, per link. A static SPA
cannot do that. That is a rendering layer, not a proxy, and it is a different
purchase from this one.

## Part 2 — `#99`: option 3, the content-type ban

The standing rule said v1's CSRF mitigation is `SameSite=Lax` plus no
state-changing GET. `#99` showed the rule reads as closed when it is not:
`SameSite` is a **site** control, our web and API hosts share a registrable
domain, and so a compromised sibling host is same-site with the API. What has
actually been holding the line is the CORS preflight that JSON bodies force —
and `multipart/form-data` is a CORS-safelisted content type, so v1's CSV
import would walk straight through it.

**Option 1 — put the API on a different registrable domain — is out.** The
intended direction is that the front is our only public face; we are not
buying a second registrable domain to make `Lax` mean what the rule claims.

**The call is option 3.** No endpoint accepts `multipart/form-data`,
`application/x-www-form-urlencoded`, or `text/plain`. A CI sweep over every
handler's `consumes` enforces it.

Why this over option 2 (promote `#48`'s CSRF token to a requirement):

- It closes the hole with a **constraint** instead of a **mechanism**. A token
  is code to issue, store, rotate, and get wrong; a banned content type is a
  list of three strings.
- **It is mechanically checkable, so it does not live in prose.** A new
  handler that accepts multipart turns CI red. That is the repo's own
  admission test: something a test can enforce should not also be a rule in an
  `AGENTS.md`.
- The preflight stops being an undocumented load-bearing accident and becomes
  the stated gate.

A CSRF token remains available as defense in depth under `#48`. This decision
demotes it from "required before import" to "not required".

**The cost lands on `#20`.** CSV upload cannot use multipart. It takes raw
bytes under a non-safelisted content type (`application/octet-stream`), or
base64 inside JSON — either forces a preflight. `#20` picks; the constraint
binds either way.
> ⚠️ **`application/octet-stream` is wrong here — it does not force a
> preflight.** Corrected below; `#20` must not take this sentence as guidance.

## Amended 2026-08-15 — the ban was half a rule, and one of its facts was wrong

Building the sweep (`#111`) tested the claims above instead of restating them,
and two did not survive.

**1. A ban does not reach a handler with no body, so the rule is now
positive.** The three-type ban only bites where a message converter runs.
`POST /auth/logout` reads no body and declared no `consumes`, so nothing
produced a 415 — and a `text/plain` POST from a sibling host is CORS-simple,
same-site under `Lax`, and arrived. The exposure was small (logout is
idempotent and reads nothing); **the sentence claiming the preflight was the
gate was the real defect**, because it read as closed.

The rule becomes: **every state-changing handler declares a non-empty
`consumes`, and no declared type may be one a preflight-free request can
present.** That subsumes the three-type ban — requiring a type that preflights
cannot also admit one that does not — and closes the body-less hole, because
`consumes` is a *mapping condition* evaluated before any converter: a
non-matching request never enters the method. Verified over real HTTP, not read
off the source: `text/plain` → 415, **no `Content-Type` header at all** → 415,
`application/json` → 204.

**Two spellings satisfy that sentence and defeat it, so the check refuses them
outright.** Both were found by auditing the check rather than the code, and
both were confirmed against the running server:

- **`@RequestBody(required = false)` switches the condition off.**
  `ConsumesRequestCondition` short-circuits before comparing any media type
  when the request has no body and the body is not required, and the only
  thing that lowers `bodyRequired` is that annotation. A handler with a
  perfectly correct `consumes = application/json` and an optional body
  answered **204** to both a `text/plain` and a header-less POST. So the
  mapping-condition guarantee above is **conditional, not absolute** — it
  holds because the sweep now forbids the one configuration that breaks it.
  Keep the body required and let the client send `{}`.
- **Any negated expression widens the condition, even beside a positive one.**
  Matching is an OR across expressions and a negated one matches everything it
  does not exclude, so `["application/json", "!multipart/form-data"]` answers
  **204** to `text/plain`. "At least one positive expression" is therefore not
  enough; **no negated expression is allowed at all**. This matters because
  `["!multipart/form-data", "!text/plain"]` is the literal transcription of
  this record's *original* wording — the spelling someone reading the
  superseded sentence would reach for.

**The sweep cannot see filter-registered endpoints**, so those are held by a
separate boot test asserting `GET /logout`, `POST /logout`, `POST /login` and
`GET /login` all 404. Two things learned there: `logout { disable() }` **is**
load-bearing — deleting it makes `GET /logout` a 302, a state-changing GET —
while `formLogin { disable() }` is **not**, because Spring Security 6 applies
`logout` to every chain by default and does not apply `formLogin`. The test
asserts the surface rather than those two lines, so it holds whichever way a
future edit reaches for them. `#5` will edit this chain.

This is deliberately not "must declare exactly `application/json`". That form
would turn red the day `#20` picks a type for CSV, forcing a decision this
record leaves to `#20`.

**2. `application/octet-stream` does not force a preflight**, so the `#20`
guidance above is wrong. A request sending **no** `Content-Type` is matched as
`application/octet-stream`, and a `fetch` with a typeless body sends none and
is CORS-simple — so octet-stream is a fourth preflight-free type. Confirmed by
declaring it on a handler: the header-less POST answered 204 and the handler
ran. **`#20`'s live options are base64 inside JSON, or a content type of our
own.**

**Consequences that landed with it:** `POST /auth/logout` now requires
`Content-Type: application/json` despite having no body, and `web/`'s
`apiFetch` sends it on every non-GET — it did not, so sign-out was broken by
the backend change until the same branch fixed it. `DELETE` is not swept: it is
never CORS-simple, so it is not a hole.

## What this does not decide

- **The prod domain names, and whether the API is hidden** — `#96`.
- **The upload encoding** — `#20`, under the constraint above.
- **CSP and `Referrer-Policy` content** — `#48`.

Refs `#99`, `#96`, `#48`, `#46`, `#20`, `#37`
