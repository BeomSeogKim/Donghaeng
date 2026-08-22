# Decision — the seat name edit: a PUT on `me`, and nobody pre-fills an empty seat (2026-08-22)

`#187`, the backend half of `#175`. Small endpoint, three decisions that outlive it:
what a single-required-member update is shaped like, who may write a name that is not
theirs, and when a mutation may answer without the aggregate. `#12`, `#13` and `#14`
are all written after this one and each of them will reach for one of the three.

The gap it closes: after 2026-08-22 the name is an attribute of the seat, and it enters
in two places that are both once-only — `POST /weddings` and `POST /weddings/join`.
**There was no third place, so a typo was permanent**, in a value that rides in
`seats[]` on every wedding response and that the 원장 header renders.

## 1. `PUT`, one required member, and no `Patch`

`PUT /weddings/{weddingId}/seats/me` with `{"name": "김신랑"}`.

`2026-08-22-decision-partial-update-shape.md` §1 predicted this would be a `PATCH`
inheriting its rule, and `docs/api-spec.md` said so too. **It is not, and both have
been corrected in this change.** The partial-update contract is a package: every member
optional, an omitted member not written, `{}` a legal no-op. **Every clause of it is
about a body with more than one member.** With one required member there is no "leave
the name alone" to express — that is not sending the request — so a `PATCH` here would
either publish a `PATCH` whose member is required, contradicting the rule at the top of
the file for every other endpoint, or accept `{}` as a no-op nobody asked for.

A first draft of this record argued a second point — that `Patch<String>` would let
`{"name":""}` through as `Set(null)` — and **it was wrong**: `PatchDeserializer` refuses
a payload that reads as null, which `#173`'s own record states in bold. It is struck
rather than quietly dropped, because the paragraph above does not need it and a record
`#12`–`#14` will read must not carry a false reason beside a true one. What a `Patch`
would still cost here is real and smaller: two validators written against the wrapper,
one of them a second spelling of the name rule.

**The general rule this leaves behind**: a body with a single required member is a
`PUT`; a body whose members are independently optional is a `PATCH`. Not a preference —
it is the partial-update contract only being true of the second kind.

## 2. The empty seat cannot be pre-filled, and the endpoint makes it unrepresentable

The question `#187` was written to settle: a couple sitting together may well want to
type "신부: 김OO" before sending the invite. **They cannot.**

아무도 남의 이름을 대신 적지 않는다 is why the partner's seat is created empty at all
([2026-08-22-decision-the-couples-two-seats.md](2026-08-22-decision-the-couples-two-seats.md)),
and the rule is about *who writes a name*, not about *which screen it is written on*. A
name typed by the other partner is the same wrong value whether it is typed during
onboarding or afterwards in 설정 — and this one would be worse than the onboarding
version was, because the person it describes may then arrive to find a name already
standing for them.

**The mechanism is the address, not a check.** The endpoint is `.../seats/me`: no seat
id, no `side`, in neither path nor body. There is no request that names the other seat,
so there is nothing to validate and nothing that can be forgotten by the next handler
written near it. A runtime refusal would have been a rule living in a service; this is
a rule living in the URL space.

What fills the waiting seat is the invite, and the person accepting types their own
name — the flow already exists, so nothing is lost by refusing to duplicate it.

**This does not decide what 설정 renders for an empty seat.** That is copy on a screen
and belongs to `web/`, exactly as §5 of the two-seats record left it. What the API says
is only that there is no field to submit.

## 3. A mutation may answer without the aggregate when the number cannot move

Root `AGENTS.md`: **every mutation response carries the recomputed aggregate**. This one
does not, and the reason is narrow enough to state as a test rather than a judgement:
**인원수 is a fold over the 하객 ledger, and a seat's name is not one of its inputs.**
There is no recomputed number because nothing was recomputed.

The precedent is not new. `POST /weddings/join` writes this very column and answers a
bare `WeddingResponse`; `POST /weddings/{weddingId}/invite` answers no aggregate either.
The rule's own justification is that 원장과 인원수는 한 화면이고 탭 한 번이 숫자를
움직인다 — where a tap cannot move the number, publishing one invites a client to
believe something was re-counted.

**This is what keeps the write in `wedding/`.**
[2026-08-22-decision-partial-update-shape.md](2026-08-22-decision-partial-update-shape.md)
§3 forecast that `#175` would be assembled in `guest/` like `PATCH /weddings/{weddingId}`,
and that sentence is conditional on the response carrying the number: *any endpoint that
mutates a wedding-scoped resource **and answers with the number** is assembled on the
ledger's side of the arrow.* With no number the premise fails, and the row stays with
the package that owns it. That note and `WeddingUpdateService`'s KDoc are amended in
this change.

The response is the **whole wedding**, not the one seat: the 원장 header renders the
pair, and `WeddingResponse` is the shape every screen already holds.

## 4. The name rule is now one annotation, because there were about to be three copies

`@NotBlank @Size(max = 100)` was written out on `CreateWeddingRequest.name` and again on
`JoinWeddingRequest.name`. A third copy is how a name refused on the screen that creates
it becomes a name accepted on the screen that fixes it.

`@SeatName` carries both checks, and all three requests wear it. Two constraints rather
than one so each keeps its own message — 101 characters and a string of spaces are still
told apart.

**And gathering them found a bug that had been sitting under all three write points.**
`@NotBlank` is not the same function as the `trim()` the services call: Hibernate
Validator trims with **Java**'s `String.trim()` (characters ≤ U+0020 only), while
Kotlin's `trim()` also strips U+3000, U+00A0 and U+2000–U+200A. **U+3000 is an ordinary
key on a Korean IME**, so `{"name":"　"}` passed validation, was emptied by the service's
trim, and was stored as `''` — `wedding_party.name` has no CHECK. `@SeatName` therefore
validates the **trimmed** value, with the same function the write points use, and one
annotation fixes 웨딩 만들기, 초대 수락 and this edit at once. `@Size` stays composed and
measures the value as sent, a bound the trim can only make slacker.

That is the argument for gathering a rule into one place, stated as something that
happened rather than as a principle: two copies were wrong in the same way and nobody
could see it until they were one.

## What this does not decide

- **Whether a seat can be released and re-invited**, still open from the two-seats
  record §5. Nothing here touches `deleted_at` or `user_id`.
- **An audit trail for the seat.** There is none — `guest_change` is the ledger's
  (`#179`, `2026-08-22-decision-the-audit-trail-waits.md`) — and with each person able
  to write only their own seat, "누가 고쳤나" has one possible answer anyway.
- **Whether `POST /weddings` should stop taking a name** now that one can be fixed
  later. It should not: a wedding with no name on either seat renders a header with
  nothing in it, and the person creating is right there.

Refs `#187`, `#175`, `#9`, `#12`, `#13`, `#173`,
[2026-08-22-decision-the-couples-two-seats.md](2026-08-22-decision-the-couples-two-seats.md),
[2026-08-22-decision-partial-update-shape.md](2026-08-22-decision-partial-update-shape.md),
[2026-08-20-decision-mutation-response-envelope.md](2026-08-20-decision-mutation-response-envelope.md),
[2026-08-21-decision-the-headcount-endpoint.md](2026-08-21-decision-the-headcount-endpoint.md).
