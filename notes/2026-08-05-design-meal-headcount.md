# Design — meal headcount and the 보증인원 decision (2026-08-05)

Closes two of the three items left open by
[2026-08-03-design-domain-model.md](2026-08-03-design-domain-model.md): the
식대 보증 인원 definition and meal granularity. Along the way it corrects two
earlier framings that rested on wrong assumptions about how Korean weddings
actually run.

## The correction that drove everything: 보증인원 is the venue's number

Earlier notes treated 식대 보증 인원 as a number this product computes. It is
not. The venue fixes a guaranteed headcount — **guests and meals are one and
the same count** — and the couple commits to it **1–2 weeks before the
wedding**. The venue then prepares roughly +X% beyond the guarantee; anyone
above it is simply billed as an extra meal.

That makes the risk asymmetric:

- Fewer guests than the guarantee → the difference is paid for anyway. Pure loss.
- More guests than the guarantee → extra meal fees only, absorbed by the buffer.

So a couple rationally commits slightly *below* their estimate, and the floor
on how low they can go is the venue's buffer — go under it and guests don't
get fed.

**Consequence: we do not recommend a 보증인원 number.** How far below the
estimate to commit is a function of the venue's buffer and the couple's
temperament, neither of which we know. What the couple actually asked for is
simply "현재 우리 식대 인원이 어느 정도구나" — one honest number. Producing
that well is the whole job.

## 1. The number

    식대 인원 = Σ over guests ( confirmed meal count, else expected meal count )

    shown beside it: 아직 모르는 N명 · 검토 필요 M건

Per guest, a confirmed value wins; absent one, the couple's expected value
stands in. A `needs_review` guest has no usable confirmed value, so it falls
back to expected and is counted in M. Every input to the sum is therefore
either a real response or a value the couple typed themselves — fully
traceable, no hidden model.

At the 1–2 week decision point most guests are already accounted for (see §3),
so N is small. Showing N honestly next to the number is enough uncertainty
disclosure; nothing more elaborate is warranted.

## 2. Meal granularity: response is a boolean, the ledger is a count

- **`RsvpResponse.meal` — party-level boolean, nullable.** Forced, not chosen:
  the vendor email carries one 식사 여부 for the whole party, and the standing
  "two channels, one response model" rule (2026-07-30) means our own link
  cannot ask for more than the vendor channel can express.
- **`Guest` meal slots — integer counts** (0 … party size), expected and
  confirmed as usual.
- **Projection**: `meal=true` → `1 + companion_count`; `meal=false` → `0`.

The asymmetry is the point. The venue is owed a count, not a yes/no, and the
couple editing that count is the only way to express "3명 중 2명만 식사". The
already-committed promise that the couple fixes exceptions from the review
queue is unachievable with a boolean slot — there would be nothing to fix.

## 3. Manual entry is a primary channel, not a fallback

Attendance information normally reaches the couple **off-channel** — through
parents, KakaoTalk, a phone call. By the 1–2 week mark the couple typically
knows about nearly everyone, whether or not those people ever touched an RSVP
link.

- 2026-07-30 framed manual entry as the fallback for unsupported vendor
  templates. It is in fact the **highest-volume path** into the ledger.
- So the ledger must let the couple set attendance in one or two taps. This is
  a first-class action in the main ledger view, not a form to fill.
- The 2026-08-03 rule — a confirmation the couple heard by phone is recorded
  as an `RsvpResponse` with `origin=manual` — stands, and matters more now
  than when it was written: it is what keeps "what the couple guessed at
  invitation time" separate from "what the couple actually verified".

## 4. "응답률" is replaced by "미확인 인원"

MVP requirement C listed "response rate + non-responder list". Response rate
measures *our channels*, not the couple's problem — it can read 40% while the
couple knows 95%. It is the wrong number to put on the screen.

Replaced by **the count and list of guests whose attendance is still
unknown**, independent of how any given guest became known. That is the list
the couple acts on ("누구를 찔러야 하나"), and it is the same N that sits
beside the headcount in §1.

## Withdrawn during this session

Recorded so they don't get re-proposed:

- **A floor / recommended / ceiling three-number band** — over-engineered once
  the decision point is late enough that N is small.
- **Any statistical adjustment of non-responders** (응답률 기반 보정) — a
  hidden model inside a money number, straightforwardly against 정직함·믿음직함.
- **Per-companion meal answers** — inexpressible on the vendor channel, and it
  would put a second field on the guest form against the 30-second target.

## Still open

- [ ] Screen and flow design — now the only thing between here and building.
- [ ] Whether `Wedding` stores the contracted 보증인원 so the screen can show
      "estimate vs. guarantee" rather than a bare number. Raised, not decided.
