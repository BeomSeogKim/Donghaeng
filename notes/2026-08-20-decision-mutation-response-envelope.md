# Decision — a mutation response is `{resource, headcount}`, and the no-envelope rule narrows (2026-08-20)

> **Amended 2026-08-22** — the rule below is stated too widely. A mutation
> carries the envelope when its resource **participates in the headcount**,
> which today means mutations under `/weddings/{id}/guests` and nothing else.
> The seam-additivity argument here is untouched and is what fixes the
> predicate — see `2026-08-22-decision-the-envelope-rule-narrows.md` (`#196`).

`#134`. Two files were telling the same rule differently, which root `AGENTS.md`
names as how rules drift. Settling it before a third endpoint copies either one.

## The disagreement

`api/AGENTS.md` (from `2026-08-07-decision-backend-api-conventions.md`) said:

> **Success responses have no envelope** — the resource's own DTO, returned
> directly. One first-party client, no pagination, no API versioning, so a
> `{data: ...}` wrapper buys nothing.

`docs/api-spec.md`'s Entry template has, since the spec was first written, drawn a
mutation response as `{ "guest": {…}, "headcount": {…} }`.

Both were right about what they were aimed at, and the collision is only visible
now because `#134` is the first mutation on a wedding-scoped resource.

## What is actually being forbidden

The 08-07 rule is aimed at a **generic** wrapper — `{data: …}`, the same shape for
every endpoint, carrying no information. That rule stands. It is not aimed at a
response that carries *two things*, and the second thing here is a root-level
product fact older than either file: **every mutation response carries the
recomputed aggregate**, because the ledger and the headcount are one screen and
tapping attendance moves the number in place.

So `api/AGENTS.md` narrows:

> **No generic envelope (`{data: …}`).** A read returns the resource's own DTO
> directly; a **mutation on a wedding-scoped resource** returns
> `{resource, headcount}` — the ledger and the headcount are one screen.

## Why the shape ships before the number does

`#17` is not built, and what the headcount counts is not yet decided — deleted
rows, whether confirmed overrides expected, whether 유아 인원 sits inside the meal
count or beside it. Counting now would produce a wrong number, which is the one
thing this product may not ship.

But the seam is type-checked (`#39`): `web/` generates its types from this response.
A response that is `GuestResponse` today and `{guest, headcount}` after `#17` is a
**changed root shape** and every call site stops compiling. `{guest}` today and
`{guest, headcount}` later is one added member — additive, nothing breaks.

So the envelope ships with the aggregate member **absent, not null**. `null` reads
as "we counted and found nothing", which is false; absent reads as "this response
does not carry that yet", which is true. `web/` reads `response.guest` from day one
and never unwraps it.

This binds `#12`, `#13`, `#14` and `#17` — four endpoints, which is more than one
endpoint's worth of decision to leave in a KDoc.
