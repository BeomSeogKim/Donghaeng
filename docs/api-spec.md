# Donghaeng API spec

The contract between `api/` and `web/`. It is a **shared asset**: the backend
maintains it, the frontend trusts it, and neither reads the other's source to
find out what an endpoint does.

## Ownership

- **`backend-implementor` owns this file.** Nobody else edits it.
- A new endpoint, a changed request or response shape, a changed status code,
  or a deprecation is written here **in the same change as the code** — never
  as a follow-up. A spec that lags the code by one commit has already broken
  the frontend's only source of truth.
- **`frontend-implementor` reads it and does not route around it.** When the
  spec is silent, ambiguous, or wrong, that is a backend change and the
  frontend stops and reports. It never guesses a shape, and it never computes
  the number client-side to work around a missing endpoint.
- Deprecation is a state, not a deletion: mark it with a date and the
  replacement, and leave the entry until the frontend has moved off it.

## Entry format

Each endpoint gets one section, in this shape:

````
### `POST /weddings/{weddingId}/guests/{guestId}/attendance`

Status: active (added 2026-08-07)
Auth: session token, membership in the wedding

Request
```json
{ "attendance": "ATTENDING | NOT_ATTENDING | UNKNOWN" }
```

Response 200
```json
{ "guest": { ... }, "headcount": { ... } }
```
Carries the recomputed aggregate: **yes** — `headcount`.

Errors
- 404 — guest not in this wedding (never 403; do not confirm existence)
````

Two fields are not optional on any mutating endpoint:

- **Carries the recomputed aggregate** — every mutation response does. The
  ledger and the headcount are one screen; a tap moves the number without a
  second round trip. If the answer is "no", say why.
- **Errors** — including which ones deliberately hide existence.

## Errors

_Added 2026-08-10. Applies to every endpoint, present and future._

Every error response **this application produces** is an **RFC 9457 Problem
Details** document served as `application/problem+json`
(`notes/2026-08-07-decision-backend-api-conventions.md`) — whatever raised it,
and whether or not it reached a controller. A success response has no envelope;
an error is the only shape the API wraps.

One boundary, stated because it is observable rather than because it is likely.
A request the HTTP connector rejects **while parsing the request line** — a
malformed request target such as `GET /a|b` — never reaches this application at
all, and is answered by the servlet container with a short HTML `400`. No client
library will send one; it takes a raw socket. That page carries no application
data (no exception, no stack trace, no server version — asserted on the shipped
image in CI), but it is not a problem document.

**For `web/`: a 4xx whose `Content-Type` is not `application/problem+json` means
the request was malformed before it arrived.** There is no `code` to switch on.
Treat it as a bug in the caller, not as an API error.

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Guest not found.",
  "instance": "/weddings/12/guests/99",
  "code": "GUEST_NOT_FOUND"
}
```

| Member | Meaning |
|---|---|
| `type` | Always `about:blank` today. Reserved; do not branch on it. |
| `title` | The HTTP reason phrase. For humans reading a log, not for the UI. |
| `status` | Repeats the HTTP status code. |
| `detail` | English, diagnostic, **not user-facing copy**. Free to change at any time. |
| `instance` | The request path that produced the error. |
| `code` | **The only member the client branches on.** See below. |

`type`, `title`, `status`, `instance` and `code` are always present. `detail` is
present in practice but is the one member with nothing guaranteeing it.

**`detail` is never rendered.** Korean copy is chosen from `code`; `detail` is
for a human reading a log. That is not only a copy rule — some of Spring's own
`detail` strings embed the submitted value verbatim (a type mismatch produces
`Failed to convert 'id' with value: '…'`), so a UI that printed `detail` would
be painting attacker-supplied text onto the screen.

### `code` — the stable string

`code` is a `SCREAMING_SNAKE_CASE` string naming *which* error this is, so the
frontend picks its Korean copy by switching on it. It exists because the
alternatives are worse: HTTP status alone cannot tell two 409s apart, and
parsing `detail` couples the UI to an English sentence nobody promised to keep.

Three rules, and they are the contract:

- **Never switch on `detail`, `title`, or `type`.** Only on `code`.
- **A `code` is stable once published.** Renaming one is a breaking change and
  goes through deprecation like an endpoint would.
- **Handle an unrecognised `code` as a generic failure of that HTTP status.**
  New codes arrive with new endpoints; the client must not break on one it has
  never seen.

Codes that exist today — all framework-level, since no domain endpoint has
shipped yet:

| `code` | Status | Raised when |
|---|---|---|
| `VALIDATION_FAILED` | 400 | A request failed Bean Validation — a request body DTO, a query parameter, or a path variable alike. |
| `MALFORMED_REQUEST_BODY` | 400 | The request body could not be parsed at all. |
| `UNAUTHENTICATED` | 401 | The request carried no session, or one that has expired, been revoked, or does not match. |
| `OAUTH_LOGIN_DENIED` | 401 | The person refused consent at the provider. **Only reachable where no frontend origin is configured** — otherwise the callback redirects; see `GET /login/oauth2/code/google`. |
| `OAUTH_LOGIN_FAILED` | 401 | The OAuth callback did not complete for any other reason. Same caveat. |
| `INTERNAL_ERROR` | 500 | Anything unhandled. See masking below. |
| *the HTTP status name*, e.g. `METHOD_NOT_ALLOWED`, `NOT_FOUND`, `UNSUPPORTED_MEDIA_TYPE` | as named | A framework-level error with no more specific code. |

Domain codes (`GUEST_NOT_FOUND`, `IMPORT_FILE_ALREADY_PROCESSED`, …) are added
to this table by the endpoint that can raise them, in the same change.

### 5xx is masked

A 5xx response says nothing about what went wrong
(`notes/2026-07-30-decision-network-security.md`). `detail` is always exactly
`"An unexpected error occurred."` and `code` is always `INTERNAL_ERROR`, no
matter what was thrown — no exception class name, no stack trace, no SQL, no
file path, and no server-authored reason string. The diagnosis lives in the
server log.

Consequence for `web/`: a 500 is never explained to the user beyond "something
went wrong, try again". There is deliberately no information to show.

### Status codes

| Outcome | Status |
|---|---|
| Read succeeds | 200 |
| Created | 201 |
| Update / computed action succeeds | 200 |
| Delete succeeds | 204 |
| Request fails validation (body, query parameter, or path variable) | 400 |
| Resource not found | 404 |
| Resource exists, but the caller is not a member of its wedding | **404** |
| State conflict (duplicate, stale write) | 409 |
| Not authenticated | 401 |
| Member of the wedding, but lacks a privilege within it | 403 |
| Anything unhandled | 500, masked |

Two rows there need reading together
(`notes/2026-08-10-decision-cross-tenant-status-code.md`).

**404 is the default for every cross-tenant refusal**, not a per-endpoint
exception one has to remember. Any resource addressed by a caller-supplied id —
a wedding, a guest, an import — answers 404 to a caller who is not a member,
exactly as it would if the id did not exist. Nothing an endpoint writes in its
own `Errors` section overrides this.

**403 means the caller is inside the wedding and lacks a privilege there.** v1
has no roles, so **403 has no correct use today**. Reaching for it because "403
is the authorization status" is the mistake this row exists to prevent.

## Authentication

_Added 2026-08-12 (`#37`). Google only for now; 카카오 · 네이버 arrive with `#89`
and add nothing to this section but a second and third `provider` path segment._

**The couple is authenticated; guests never are.** Login is OAuth at the
provider, and what the browser ends up holding is an **opaque session token in an
HttpOnly cookie** — not a JWT, not the provider's token, and nothing the frontend
can read or needs to.

### The cookie

| | |
|---|---|
| Name | `DH_SESSION` |
| Flags | `HttpOnly`, `SameSite=Lax`, `Path=/`, no `Domain`; `Secure` everywhere except local dev over `http://localhost` |
| Lifetime | idle **28.75–30 days**, absolute **180 days** — whichever comes first |

**`web/` never reads, writes, or parses this cookie.** It cannot: `HttpOnly`.
Every request simply needs to be sent with credentials included
(`fetch(..., { credentials: 'include' })`), and the answer to "am I logged in?"
is `GET /auth/me`, never an inspection of `document.cookie`.

**Two expiries, and they are different questions.** Idle is measured from the
last request, absolute from the moment of login. A couple using the app daily is
still signed out after 180 days and logs in again; that is intended — the
absolute window is not extended by use, which is the whole difference between the
two.

The numbers come from how the product is used, not from a security threshold
(`notes/2026-08-12-decision-session-lifetimes.md`): **a wedding is planned over
about a year and the couple open this a few times a month.** A short idle window
would sign a monthly user out on every single visit.

The idle window is a **range, not 30 days exactly.** The server does not rewrite
the "last seen" stamp on every request — that would make every read a write — so
the stamp can lag real activity by up to 30 hours, and a session expires
somewhere between 28.75 and 30 days after its last use. Nothing expires *later*
than 30 days. Do not build a countdown from this; ask the server.

**The session is re-issued on every login**, which invalidates the token the
browser presented. Tabs share one cookie jar, so a second login in another tab
simply replaces the cookie for both — the first tab is not signed out. **A second
device is a separate session and is not revoked**, by design: signing in on a
laptop does not sign the phone out.

Treat a 401 as "log in again", never as an error state to report.

### `GET /oauth2/authorization/google`

Status: active (added 2026-08-12)
Auth: none — this is where a logged-out person starts

**A browser navigation, not a fetch.** Point the browser at it
(`window.location.href = ...`, or a plain `<a href>`); an XHR cannot follow the
redirect chain to Google and back, and calling it with `fetch` will fail on CORS
at the provider rather than logging anyone in.

Responds `302` to Google's consent screen.

Errors
- 404 — there is no such login provider **here**. Two things produce it and they
  are deliberately indistinguishable: a provider name we do not support, and an
  environment where Google credentials are not configured. In the second case the
  backend logs a warning naming the two missing variables at startup; every other
  endpoint works normally, so a frontend can be developed against a backend that
  cannot log anyone in.

Not in the generated OpenAPI document — it is a Spring Security filter, not a
controller. Same for the callback below.

### `GET /login/oauth2/code/google`

Status: active (added 2026-08-12; failure behaviour changed 2026-08-13, `#109`;
first-login idempotency stated 2026-08-13, `#93`)
Auth: none — this is what the provider redirects the browser back to

**Nothing calls this; Google does.** It is listed because its outcomes are the
frontend's to handle, and because the exact URL
`http://localhost:8080/login/oauth2/code/google` in dev is registered by hand in
the Google console and must not change.

On success: `302` to the configured frontend origin (dev: `http://localhost:3000`)
with `Set-Cookie: DH_SESSION=...`. **The destination is server configuration.** It
is never taken from the request, so there is no `returnTo` parameter and adding
one would be an open redirect on the one request that has just been handed a
session.

**A first login is idempotent** (`#93`,
`notes/2026-08-13-decision-first-login-idempotency-and-email-merge.md`). Two
callbacks for the same identity landing at the same instant — two tabs, a double
tap, a retried navigation — both succeed and both get a session; the first
registers the account and the second signs in on it. This is stated because the
frontend used to have to treat "log in again and it works" as a real strategy: the
losing one answered 500. There is nothing to handle and no new status code —
success looks the same either way, and **a 500 from this endpoint is now always a
genuine fault.**

On failure: `302` to **`<frontend origin>/login`** with a code in the **URL
fragment**, and no session cookie (changed 2026-08-13, `#109`,
`notes/2026-08-13-decision-login-failure-return-path.md`). The callback is a
browser navigation, so a JSON body here would be a document the person is left
looking at with no way back.

| Fragment | Means |
|---|---|
| `#e=denied` | The person refused consent at the provider. **A normal path, not an error** — land on the ordinary login screen with the button offered again, never on an error screen. |
| `#e=failed` | Everything else: a `state` mismatch, a failed token exchange, a provider error, an ID token that did not validate. |

Three properties of that redirect are contract, and `web/` should rely on all
three:

- **Two codes, and that is the whole vocabulary.** A third value is a spec change,
  not a surprise to handle. **Switch on the value; never render it**, and treat an
  unrecognised one as `failed`. That is not defensiveness about us: anyone can
  send a victim to `<frontend>/login#e=<anything>` without touching this API, so
  at the frontend the fragment is fully attacker-controlled.
- **Nothing the provider wrote ever appears.** No `error_description`, no OAuth
  error code, no exception message. **The frontend owns every word the user
  reads**; the Korean copy is a constant per code in `web/`.
- **A fragment, never a query string.** There is no `?error=`. A fragment is not
  sent to a server, so the reason stays out of access logs and out of `Referer`.
  Read it with `location.hash`, and clear it once handled.

The origin is server configuration — the same value the success redirect uses.

Errors (problem+json, and only where there is no frontend to return to)
- 401 `OAUTH_LOGIN_DENIED` / 401 `OAUTH_LOGIN_FAILED` — an environment with
  `donghaeng.frontend.base-url` unset has nowhere to redirect, which is
  production's state until `#96`. No browser `web/` serves ever sees these; they
  are documented so the two codes are not read as removed.

### `GET /auth/me`

Status: active (added 2026-08-12)
Auth: session cookie

The first call on every page load: who is signed in, if anyone.

Response 200
```json
{ "id": 12, "name": "김테스터" }
```

`name` is **nullable** — a provider may return none — so render a fallback rather
than assuming a string.

**There is deliberately no `email`** (decided 2026-08-12). No v1 screen shows the
couple their own address, and publishing a field nothing consumes would be a seam
commitment with no requirement behind it. Ask if a screen needs it; do not work
around its absence. This says nothing about what the server *stores* — the
verified-email account merge is untouched and is not visible here.

Carries the recomputed aggregate: **no.** It is a read, and it is not
wedding-scoped: which wedding is a separate resolution that arrives with `#5`.

Errors
- 401 `UNAUTHENTICATED` — no cookie, or an expired, revoked or unrecognised one.
  One code for all of those on purpose: distinguishing them would tell an
  anonymous caller which session identifiers exist.

### `POST /auth/logout`

Status: active (added 2026-08-12)
Auth: session cookie, but see below — it never demands one

Ends the session **on this device**.

Request: no body.

Response 204, with no body and a `Set-Cookie` that clears `DH_SESSION`.

Carries the recomputed aggregate: **no** — it changes no ledger data.

Three properties, and each exists because its absence would produce a sign-out
button that leaves people signed in:

- **It is a POST, and a GET will not do.** v1's CSRF protection is
  `SameSite=Lax` plus no state-changing GET, and Lax *does* send the cookie on
  top-level GET navigation — so a logout reachable by GET could be triggered by an
  `<img>` on any page the couple visit. Under POST the cookie is withheld
  cross-site and the request cannot revoke anything.
- **It always answers 204**, whatever it finds: no cookie, an unparseable cookie,
  several at once, an expired session, one already revoked, one revoked from
  another device. All of them mean "you are not logged in on this device", which
  is what the caller asked for. There is no error path to write, and it is
  idempotent — calling it twice is not a mistake.
- **If the browser somehow presents more than one session cookie, every one of
  them is ended.** Reads refuse an ambiguous request (that is where a 401 with
  more than one cookie comes from); logout is the opposite and deliberately so.
  This is the right thing to call when the app is wedged on 401s.
- **It does two things, and the client needs both.** The server revokes the
  session row, which is what makes the token dead everywhere; the response clears
  the cookie, which is what stops the browser from presenting a dead token on
  every later request. Deleting the cookie client-side alone is *not* logout — the
  row stays valid and the token would still work if it were ever presented again.

**It signs out this device only.** The couple share one ledger and use each
other's phones, so a laptop logout leaves the phone signed in — by design. Signing
out everywhere is a separate, not-yet-built feature; do not present this as one.

Errors: none. Handle 204 and nothing else.

### Calling the API from the browser (CORS)

_Added 2026-08-12 (`#97`)._

The API and `web/` are different origins in every environment, so **every call
from the browser is a cross-origin call** and two things have to be true at once.

1. **The origin must be on the server's list.** It is an exact string — scheme,
   host and port — and there is no wildcard and no pattern. In dev the list is
   exactly `http://localhost:3000`. `http://127.0.0.1:3000` is a *different*
   origin to a browser and is not on it; use `localhost`.
2. **The request must be sent with credentials.** `fetch(url, { credentials:
   'include' })`, or `withCredentials` on whatever client you use. Without it the
   browser sends no cookie, the server sees an anonymous request, and `/auth/me`
   answers 401 — which looks exactly like "not logged in" and is the single most
   likely way to lose an afternoon here.

Allowed methods are `GET, POST, PATCH, PUT, DELETE`; allowed request headers are
`Content-Type` and `Accept`. **A custom header needs a backend change** — adding
one to a request without it being on that list fails the preflight, and does so
before any application code on either side runs.

An origin that is not on the list gets no `Access-Control-Allow-Origin`, and the
browser discards the response before your code sees it. Production has no origin
configured yet, for the same reason it has no frontend URL (`#96`).

### Not here yet

- **Signing out of every device.** `POST /auth/logout` ends this device's session
  only. A "log out everywhere" action is filed and not built; do not label the
  existing button as though it were one.
- **CSRF token.** v1's protection is `SameSite=Lax` plus no state-changing GET; a
  token is `#48`.

## Endpoints

_No wedding-scoped endpoints yet. The error contract and the authentication
section above are live and binding._
