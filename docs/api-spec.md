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

## Endpoints

_None yet — `api/` has not been scaffolded._
