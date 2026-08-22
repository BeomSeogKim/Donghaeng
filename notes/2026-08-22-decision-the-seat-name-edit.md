# Decision — the seat name edit: a PUT on `me`, and nobody pre-fills an empty seat (2026-08-22)

`#187`, the backend half of `#175`. Small endpoint, four decisions that outlive it: what
a single-required-member update is shaped like, who may write a name that is not theirs,
when a mutation may answer without the aggregate, and — the founder's, added in review —
**what makes a string a name at all** (§5), which binds every name field in the product
and not just this one. `#12`, `#13`, `#14` and `#191` are all written after this one and
each reaches for at least one of the four.

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

**And gathering them found a bug that had been sitting under all three write points**,
which is what §5 is about. `@SeatName` is where that rule lives now, so 웨딩 만들기,
초대 수락 and this edit are fixed by one annotation and `#191` copies it rather than
re-deriving one.

`@Size` stays composed and measures the value **as sent**, a bound the write point's
`trim()` can only make slacker.

~~The `maxLength` on the seam is declared to `@Schema` beside it, because a composed
constraint is not something to assume springdoc walks.~~ **Struck: springdoc does walk
it.** Verified by deleting three hand-written `@Schema(maxLength = 100)` and regenerating
the document unchanged — they were copies of the constant kept in step by nothing. What
holds the bound now is an assertion in `OpenApiDocumentTest`, **not** the `seam` job:
`openapi-typescript` drops `maxLength`, so the committed types are byte-identical whether
the bound is published or not, and a green `seam` says nothing about it.

That is the argument for gathering a rule into one place, stated as something that
happened rather than as a principle: two copies were wrong in the same way and nobody
could see it until they were one.

## 5. 보이지 않는 문자로만 된 이름은 이름으로 치지 않는다

**The founder's rule**, and it binds every name field in the product — 하객 이름 included
(`#191`), which copies this predicate rather than deriving its own.

> A name must contain **at least one visible character**: a code point that is neither
> whitespace nor in Unicode general category C (`Cc` control, `Cf` format, `Cs`
> surrogate, `Co` private use, `Cn` unassigned).

**How we got here matters, because the first two answers were both wrong and each looked
right.** `@NotBlank` and the services' `trim()` are different functions, and this record
twice asserted one contained the other. It said Java's `String.trim()` "strips only
characters ≤ U+0020, while Kotlin's **also** strips U+3000, U+00A0 and U+2000–U+200A".
**"Also" asserts containment and there is none** — the two sets merely OVERLAP, measured
against this build's kotlin-stdlib rather than reasoned about:

| Class | Java `trim` (`c <= ' '`) | Kotlin `trim` (`isWhitespace() \|\| isSpaceChar()`) |
|---|---|---|
| U+0000–U+0008, U+000E–U+001B | strips | **does not** |
| U+00A0, U+2000–U+200A, U+3000 | **does not** | strips |
| U+200B, U+FEFF, U+00AD | **does not** | **does not** |

Each row was a live defect. Row two is the bug that started this: `"　"` (U+3000, an
ordinary key on a Korean IME) passed `@NotBlank`, was emptied by the service's trim, and
was stored as `''`, since `wedding_party.name` carries no CHECK. **Row one is the
regression the first fix introduced** — validating with Kotlin's trim alone let a NUL
through, and PgJDBC refuses NUL in a text parameter, so a 400 became a masked 500: the
exact shape `CreateWeddingContractTest`'s own comment exists to prevent. Row three is
what neither trim would ever have caught, and what a union of them still would not: a
name of one U+200B passes every trim in both languages and lands as a seat labelled with
nothing.

**So the question changed rather than the net widening.** "Does it trim to empty" cannot
answer row three at any level of care, because no trim strips a zero-width character. "Is
there a visible character in it" answers all three for one reason, and the reason is the
one a person can hold: 이름은 보이는 것이다.

**Two implementation facts that are not detail, both found by measuring:**

- **It walks CODE POINTS, not `Char`s.** A supplementary-plane character is a surrogate
  PAIR, so a per-`Char` predicate excluding `Cs` reads two surrogates and calls the whole
  name invisible. The first draft refused 🙂 **and CJK Extension B hanja**, which appear
  in real names. A test holds this from the accepting side.
- **`Character.isSpaceChar` sits beside `isWhitespace`** because the `Character`
  predicate excludes U+00A0 where Kotlin's `Char.isWhitespace()` includes it. Without it
  a name of one NBSP passes.

**`Co` (private use) is refused deliberately**, not by reaching for a category list.
Nothing guarantees it renders and it is not a character a couple types; a name we cannot
draw is not a name they can read on their own ledger.

**What it does not over-reject**, since the rule is about invisibility and not about
being unusual: Hangul, hanja, kana, Latin, digits, hyphens and emoji all carry a visible
code point and all pass. `"​김​"` — zero-width, 김, zero-width — passes too, because
there *is* a visible character in it. Only a name with none is refused.

**The client cannot replicate this and should not try** — see the frontend note in
`docs/api-spec.md`. JS `String.prototype.trim()` catches the common case; the server is
the authority.

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
