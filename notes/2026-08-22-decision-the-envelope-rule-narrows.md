# Decision — 봉투는 인원수에 참여하는 리소스의 것이다 (2026-08-22)

Closes `#196`. Amends `2026-08-20-decision-mutation-response-envelope.md` and
`api/AGENTS.md`, both of which stated the rule too widely.

**The call: a mutation carries `{resource, headcount}` when the resource
**participates in the headcount** — not merely because it is wedding-scoped.**

## The drift this closes

The rule was written unconditionally — *a mutation on a wedding-scoped resource
returns `{resource, headcount}`* — and two merged endpoints already stand
outside it:

| Endpoint | Returns | Argued in |
|---|---|---|
| `POST /weddings/{id}/invite` | `{token, expiresAt}` | `#181` |
| `PUT /weddings/{id}/seats/me` | bare `WeddingResponse` | `#188` |

Each wrote its own justification into the spec and neither touched the rule, so
one rule sat in three places with two of them saying yesterday's thing. The root
`AGENTS.md` calls that drift the thing that has already cost this repo a live
bug, which is why this is a record and not a comment.

## Answering the argument nobody had answered

`#188` argued *"이름은 인원수의 입력이 아니므로 재계산될 게 없다"*. True, and
beside the point: the 08-20 record's load-bearing argument is **seam
additivity**, and it is explicitly indifferent to whether a number moves today.

> A response that is `GuestResponse` today and `{guest, headcount}` after `#17`
> changed **shape at the root** and every call site stops compiling. `{guest}`
> today and `{guest, headcount}` later is **one member more**.

That argument protects a response that **might one day carry the number**. So
the correct question is not "does this mutation move 인원수" but **"could this
resource ever be one of the headcount's inputs"** — and once it is put that way
the narrowed rule is not a weakening of additivity, it is the right predicate
for it. A resource that can never participate will never gain the member, so
there is nothing for the envelope to hold a place for. Wrapping it is not
cheap insurance; it is a member that will be `undefined` forever, and a reader
who has to ask why.

**The two endpoints above are on the safe side of that line by their nature,
not by today's accident.** A seat's name and an invite token cannot become
inputs to 식대 인원 — the headcount sums 하객 and their 참석, and neither a name
nor a credential is a guest.

## The rule, stated so it can be checked

> **Mutations under `/weddings/{id}/guests` carry `{resource, headcount}`.
> Nothing else does.**

Enumerated rather than described, because "participates in the headcount" is a
judgement and the previous wording proved a judgement is what drifts. 하객 and
their 참석 are the headcount's only inputs today
(`2026-08-21-decision-the-headcount-endpoint.md`). **When a resource joins that
list, this line and the endpoint change in one commit** — that is the trigger,
and it is the only way the rule stays true.

## What does not change

- **No generic envelope.** `{data: ...}` is still forbidden everywhere; that
  half of the convention was never in question.
- **Reads never carry it.** Unchanged, and the reason is unchanged: 인원수 has
  its own endpoint.
- **The two merged endpoints are correct as shipped** and are not being
  reworked. This record makes the rule agree with them, which is the cheap
  direction; retrofitting an empty envelope onto an invite token would be
  ceremony.
