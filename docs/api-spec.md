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

## Endpoints

_None yet — `api/` has no domain endpoints. The error contract above is already
live and binding._
