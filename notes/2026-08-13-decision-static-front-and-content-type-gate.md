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

## What this does not decide

- **The prod domain names, and whether the API is hidden** — `#96`.
- **The upload encoding** — `#20`, under the constraint above.
- **CSP and `Referrer-Policy` content** — `#48`.

Refs `#99`, `#96`, `#48`, `#46`, `#20`, `#37`
