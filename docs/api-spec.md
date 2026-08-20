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

## The generated OpenAPI document

_Added 2026-08-19 (`#39`)._

`web/` generates its TypeScript types from **`api/build/openapi.json`**. That file
is produced from the running application by

```
cd api && ./gradlew openapi
```

and is written by `./gradlew build` too, as a side effect of the test that asserts
what is in it. It is a build artifact and is **not committed**, so anything that
needs it — CI included — regenerates it.

**The document carries shapes; this file carries meaning.** A field name or a type
that disagrees between the two is a bug in the backend. Everything else below is a
property of the document the generator will meet:

- OpenAPI **3.1.0**.
- **Response bodies are keyed `*/*`, not `application/json`.** No handler declares
  `produces`, so springdoc emits the wildcard. The server sends `application/json`
  on success and `application/problem+json` on an error, always.
- **The `ProblemDetail` schema is Spring's own and does not carry `code`** — the one
  member the frontend switches on (`#66`). The error shape is the one defined above,
  not the one the document describes.
- **Only the statuses an endpoint's entry names are in it.** The universal ones — a
  415 from the content-type gate, a masked 500 — are in this file only.
- **A `consumes` is invisible.** `POST /auth/logout` shows no request body and still
  refuses a request without `Content-Type: application/json`.
- The two OAuth endpoints are absent, as their own entries say.
- **The document itself is 404 in every deployed environment.** `/v3/api-docs` is
  enabled only in the build that writes the file; do not fetch it at runtime.

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

Codes that exist today — framework-level, plus the one domain code a shipped
endpoint raises:

| `code` | Status | Raised when |
|---|---|---|
| `VALIDATION_FAILED` | 400 | A request failed Bean Validation — a request body DTO, a query parameter, or a path variable alike. |
| `MALFORMED_REQUEST_BODY` | 400 | The request body could not be parsed at all. |
| `UNAUTHENTICATED` | 401 | The request carried no session, or one that has expired, been revoked, or does not match. |
| `OAUTH_LOGIN_DENIED` | 401 | The person refused consent at the provider. **Only reachable where no frontend origin is configured** — otherwise the callback redirects; see `GET /login/oauth2/code/google`. |
| `OAUTH_LOGIN_FAILED` | 401 | The OAuth callback did not complete for any other reason. Same caveat. |
| `WEDDING_NOT_FOUND` | 404 | The wedding this request is scoped to could not be resolved for this caller. **One code for four situations on purpose** — no such wedding, a wedding the caller is not a member of, a deleted wedding, and a `{weddingId}` that is not a number. See "Being scoped to a wedding" below. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | A `POST`/`PUT`/`PATCH` sent a `Content-Type` the endpoint does not accept, **or sent none at all**. Not an edge case — it is how the CSRF gate refuses a request; see "Every POST, PUT and PATCH must send `Content-Type: application/json`". |
| `INTERNAL_ERROR` | 500 | Anything unhandled. See masking below. |
| *the HTTP status name*, e.g. `METHOD_NOT_ALLOWED`, `NOT_FOUND` | as named | A framework-level error with no more specific code. |

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

Status: active (added 2026-08-12; `name` became refreshable 2026-08-17, `#94`)
Auth: session cookie

The first call on every page load: who is signed in, if anyone.

Response 200
```json
{ "id": 12, "name": "김테스터" }
```

`name` is **nullable** — a provider may return none — so render a fallback rather
than assuming a string.

**`name` can change between two calls for the same `id`, and that is not an error
state.** It is the provider's display name, re-read at every login: someone who
renames their Google account sees the new name here from their next sign-in
onwards. So do not cache it past a login, do not treat a change as a different
person — **`id` is the identity, `name` is display text** — and do not offer an
edit for it in v1, since the next login would overwrite whatever was typed.
A provider that sends no name on a later login does **not** clear the stored one,
and the refresh is **best-effort**: a name the server cannot store leaves the
previous one standing and the login still succeeds. A name that did not change is
never a failed login.

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

Request: no body, but **`Content-Type: application/json` is required anyway**
(changed 2026-08-15). Without it the request is refused with a 415 and never
reaches the endpoint — see "Every POST, PUT and PATCH must send
`Content-Type: application/json`" below for why an empty body still needs a
content type.

Response 204, with no body and a `Set-Cookie` that clears `DH_SESSION`.

Carries the recomputed aggregate: **no** — it changes no ledger data.

Three properties, and each exists because its absence would produce a sign-out
button that leaves people signed in:

- **It is a POST, and a GET will not do.** Lax *does* send the cookie on top-level
  GET navigation, so a logout reachable by GET could be triggered by an `<img>` on
  any page the couple visit. What protects the POST is not Lax either — it is the
  preflight the required `Content-Type` forces, which is why that header is not
  optional on a request with no body.
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

### Every POST, PUT and PATCH must send `Content-Type: application/json`

_Added 2026-08-15 (`#111`). **This is a breaking change** — see the note at the end._

**Every state-changing request sends `Content-Type: application/json`, including
one with an empty body.** A `POST`, `PUT` or `PATCH` that sends anything else — or
sends no `Content-Type` at all — gets a **415**, and never reaches the endpoint.
`GET` is unaffected and sends no `Content-Type`.

**The 415 is an ordinary problem document**, like every other error here. It is
raised during handler selection rather than by the endpoint, but that changes
nothing the client sees:

```json
{
  "type": "about:blank",
  "title": "Unsupported Media Type",
  "status": 415,
  "detail": "Content-Type 'text/plain' is not supported.",
  "instance": "/auth/logout",
  "code": "UNSUPPORTED_MEDIA_TYPE"
}
```

`code` is **`UNSUPPORTED_MEDIA_TYPE`** for every way of getting refused, so the
client branches once. Only `detail` varies, in three ways — a wrong type quotes it
back (`Content-Type 'text/plain' is not supported.`), a missing header reads
`Content-Type 'null' is not supported.`, and an **unparseable** header (`%`, or a
valid type with a bad parameter such as `application/json; charset=@@`) reads
`Could not parse Content-Type.` Do not branch on any of them; the first is also a
plain example of why `detail` is never rendered, since it prints the submitted
header straight back at you.

**A test double must return this document, not a bare 415.** A double that answers
415 with no body disagrees with the server about the one member anything branches
on.

This is not a style preference; it is the one rule in this section that is also a
security control, and the header is doing real work even when there is no body to
describe. The **CORS-safelisted** content types — `multipart/form-data`,
`application/x-www-form-urlencoded`, `text/plain` — are the only ones a browser may
send cross-origin *without* a preflight, and the preflight is what stops a
malicious page from writing to this API with the couple's cookie attached. Demanding
JSON is what forces the preflight
(`notes/2026-08-13-decision-static-front-and-content-type-gate.md`). Two CI checks
hold it: one that every handler declares it, one that a request without it is
actually refused.

What it means for `web/`:

- **Send the header on every mutation, even with no body.** `fetch(url, { method:
  'POST' })` with no `Content-Type` is refused. This is the whole of the breaking
  change.
- **Never `FormData`, never `URLSearchParams`, as a request body.** `fetch` sets a
  safelisted content type for both, and the call will fail.
- **File upload does not use a multipart form**, and it will not use
  `application/octet-stream` either — a request sending *no* `Content-Type` is
  matched as octet-stream, so that type is preflight-free and banned with the other
  three. When CSV import arrives (`#20`) it will be base64 inside JSON or a content
  type of our own; the endpoint's entry will say which. An `<input type="file">` is
  still the picker; only the transport differs.

> **Breaking, 2026-08-15.** `POST /auth/logout` previously accepted a request with
> no `Content-Type` and now answers 415 to one. `web/` sent none until this date, so
> sign-out broke until `apiFetch` was changed to set the header on every non-`GET`;
> that landed alongside this. Nothing else in `web/` mutates yet. **A client written
> against an older copy of this spec will fail its first mutation** — this is the
> first thing to check.

### Not here yet

- **Signing out of every device.** `POST /auth/logout` ends this device's session
  only. A "log out everywhere" action is filed and not built; do not label the
  existing button as though it were one.
- **CSRF token.** Narrowed 2026-08-15: v1's protection is the **CORS preflight**,
  forced by the content-type rule above, plus `SameSite=Lax` and no state-changing
  GET. `Lax` alone does not close it — a sibling host shares our registrable domain
  and is therefore same-site with the API. A token stays defense in depth (`#48`),
  not a requirement.

## Being scoped to a wedding

_Added 2026-08-19 (`#5`). Applies to every endpoint whose path contains
`{weddingId}` — which, apart from `POST /weddings`, is all of them._

**Being logged in is not enough to reach a ledger.** Every wedding-scoped request
resolves `user → membership → wedding` before the endpoint runs, and the endpoint
runs only if that walk succeeds. The session does not carry a wedding: a person may
belong to several, and a couple share one, so which wedding is a property of the
request and not of the login.

**The wedding id travels in the path and nowhere else.** Never in a body, never in a
query parameter — a request that puts it anywhere else is a request that chooses its
own tenant, and no endpoint reads it from there. Do not add `weddingId` to a request
body even when it feels redundant to leave out.

Two outcomes, and the order between them is contract:

| Situation | Status | `code` |
|---|---|---|
| No session, or an expired, revoked or ambiguous one | 401 | `UNAUTHENTICATED` |
| Anything else that stops the wedding from resolving | 404 | `WEDDING_NOT_FOUND` |

**The 401 is decided first**, before the id and before the body. An anonymous
request to a wedding-scoped endpoint is 401 whatever id it names and whatever it
sends — so an anonymous caller never learns that an id exists, and never gets a
validation error listing the endpoint's fields.

**The 404 is deliberately one answer for four different situations**
(`notes/2026-08-10-decision-cross-tenant-status-code.md`), and they are
indistinguishable in every member of the document, not merely in the status:

- the wedding does not exist;
- it exists and the caller is not a member of it;
- it existed and was deleted;
- `{weddingId}` is not a number at all.

That is what stops the API from being a wedding-id oracle: a logged-in stranger
walking the id space learns nothing about which weddings exist. The cost is that
`web/` cannot tell "gone" from "not yours" either, and should not try —
**treat a 404 from a wedding-scoped endpoint as "this ledger is not available to
you", send the person back to their own starting screen, and never retry the same
id.**

**403 never appears.** It would mean the caller is inside the wedding and lacks a
privilege there, and v1 has no roles.

**How the client gets a `weddingId`:** from the `id` in the `POST /weddings`
response. There is no endpoint yet that lists the weddings a person belongs to, so a
screen that needs the list is a backend change — ask, do not infer one from a stored
id.

## Endpoints

_The error contract and the authentication section above are live and binding for
every endpoint below._

### `POST /weddings`

Status: active (added 2026-08-17, `#123`)
Auth: session cookie — **and nothing more.** This is the one endpoint in the
product that is not scoped to a wedding.

웨딩 만들기, the screen a couple sees once, between logging in and the ledger
(`notes/2026-08-07-design-screens-and-flow.md`). It creates the wedding **and the
caller's membership in it, in one transaction** — every other request resolves
`user → membership → wedding`, so a wedding created without a membership is a
ledger nobody can open, and this endpoint is where a person's first membership
comes from.

Request
```json
{ "weddingDate": "2026-10-10", "groomName": "김신랑", "brideName": "이신부" }
```

All three members are **required**. `weddingDate` is a plain calendar date
(`YYYY-MM-DD`), no time and no timezone — a wedding date is a date.

Response 201
```json
{ "id": 12, "weddingDate": "2026-10-10", "groomName": "김신랑", "brideName": "이신부" }
```

`id` is what the client did not have; the rest is echoed back **as stored**, which
is not always what was sent — see the trimming rule below. There is no `Location`
header (changed 2026-08-19: `GET /weddings/{weddingId}` now exists, but the id in
the body is what the client uses, so a header repeating it buys nothing). **Keep the
`id`** — it is the only place a client learns one.

Carries the recomputed aggregate: **no, and here that is not an exception.** A
wedding is created empty — no guests, no meal types — so there is no headcount to
carry. The client's next screen is the ledger, which reads it.

Errors
- 400 `VALIDATION_FAILED` — a name that is blank, whitespace-only, or longer than
  100 characters; or a `weddingDate` outside the range the database can store
  (before 4713 BC or after 5874897 AD).
- 400 `MALFORMED_REQUEST_BODY` — a member omitted, sent as `null`, or of the wrong
  type (an unparseable `weddingDate` among them). **Two codes, one meaning for the
  user**: the request was wrong. They differ because one failure happens while the
  body is being read and the other after; do not build different UI for them.
- 401 `UNAUTHENTICATED` — no session, or an expired or revoked one. Refused
  **before the body is looked at**, so an anonymous request with an invalid body is
  a 401 and never a 400.
- 415 `UNSUPPORTED_MEDIA_TYPE` — the standing content-type rule, in
  "Every POST, PUT and PATCH must send `Content-Type: application/json`" above.

Six things are decided here rather than left to the caller to infer.

- **보증인원 is not asked, and cannot be sent.** Couples sign up before booking a
  venue, so at this moment the venue's number does not exist; the ledger works
  completely without it and it is set later in 설정. An unknown member such as
  `guaranteedHeadcount` is **ignored**, not refused — sending it sets nothing.
- **Meal types are not asked either.** The default is a single type, and the moment
  a couple first meets meal types is when a guest needs 유아식.
- **A date in the past is accepted.** A couple building the ledger after the fact
  is a real case, and a mistyped date is editable in 설정. Do not add a client-side
  "must be in the future" rule; it would refuse people the server accepts. The only
  bound is what the column can store, which is not a product rule — if the product
  should refuse a date twenty years out, that is a decision nobody has made yet.
- **A second wedding by the same person succeeds.** One person may belong to
  several weddings, so the API does not refuse the second — the screen is what
  guides a couple to create one. Do not treat a 201 here as proof they had none.
- **Names are stored trimmed.** Leading and trailing whitespace is removed before
  the row is written, which is why the response echoes the stored value. A name
  that is *only* whitespace is a 400, not an empty name.
- **100 characters is the limit on each name**, and it is measured **on what you
  send, before that trim** — so 100 characters plus a trailing space is a 400 even
  though it would have been stored as 100. Trim in the client and the two agree.
  The count is in UTF-16 code units, so a name built from astral-plane characters
  may be refused slightly before 100 visible characters.

### `GET /weddings/{weddingId}`

Status: active (added 2026-08-19, `#5`)
Auth: session cookie **and membership in this wedding** — see "Being scoped to a
wedding" above, which is what this endpoint is the first to be governed by.

The wedding itself: the couple's names and the date, as stored. The ledger screen
reads it to render its header, and it is the call that answers "is this wedding
still mine?" after an app resume or a shared link.

Response 200
```json
{ "id": 12, "weddingDate": "2026-10-10", "groomName": "김신랑", "brideName": "이신부" }
```

The same shape `POST /weddings` returns, deliberately — one `WeddingResponse` for
both, so a client caches one type. **No `guaranteedHeadcount`** here either: nothing
sets it yet, and it arrives with `#8`, which is also where 설정 will read it.

Carries the recomputed aggregate: **no** — it is a read, and there is no aggregate
on this resource. The headcount is its own endpoint (`#17`).

Errors
- 401 `UNAUTHENTICATED` — no session, or an expired or revoked one. Decided **before
  the id is looked at**.
- 404 `WEDDING_NOT_FOUND` — no such wedding, or not the caller's, or deleted, or an
  id that is not a number. One answer for all four; do not try to tell them apart.

Two things this endpoint does **not** do, stated so nobody waits for them:

- **It does not list the couple's weddings.** There is no `GET /weddings`, and a
  screen that needs one is a backend change.
- **It is not a membership check to call before every other request.** Every
  wedding-scoped endpoint resolves membership on its own, on every request; calling
  this first would double the round trips and prove nothing about the next one.
