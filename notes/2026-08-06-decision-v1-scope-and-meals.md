# Decision — v1 scope cut, and meals become a typed axis (2026-08-06)

Reduces MVP v1 to what the couple operates directly, and promotes meals from a
derived number into a configurable, typed axis of their own. Supersedes parts of
[2026-07-26-mvp-v1-requirements.md](2026-07-26-mvp-v1-requirements.md),
[2026-07-30-design-guest-ledger-hard-spots.md](2026-07-30-design-guest-ledger-hard-spots.md),
[2026-07-30-decision-tech-stack.md](2026-07-30-decision-tech-stack.md),
[2026-07-30-decision-network-security.md](2026-07-30-decision-network-security.md),
and [2026-08-05-design-meal-headcount.md](2026-08-05-design-meal-headcount.md).

## v1 in one line

> A tool the couple operates for headcount and meal planning, plus a vendor-email
> parser that saves them typing.

## 1. Scope cut — our own RSVP links are out of v1

Two intake paths remain:

1. **The couple enters it directly.**
2. **A vendor RSVP email**, parsed from a known template.

The shared link and the per-guest link are **deferred, not cancelled**.

> **Superseded later the same day** — see
> [2026-08-06-decision-drop-response-model.md](2026-08-06-decision-drop-response-model.md).
> This note argued that keeping `RsvpResponse` split from `Guest` was what let the
> links return without a rewrite. That turned out not to justify the cost: adding
> the tables back is an additive migration with no backfill, and the split was
> already failing its own invariant. v1 writes confirmed values straight onto
> `Guest` and keeps traceability in a `GuestChange` audit log.

### What the cut removes

Listed explicitly because all of these were live requirements yesterday:

- **The entire guest-facing web bundle.** `web/` ships one bundle in v1, not two.
  The 2026-07-30 two-bundle rule survives as a constraint for when links return.
- **Enumeration safety** — there is no public page to enumerate against.
- **Shared / per-guest link tokens**, their per-link rate limits, the same-device
  re-submit marker, and silent duplicate detection.
- **"The guest page is the brand's first impression"** (MVP req B). No guest meets
  the product in v1 at all.
- **The review queue, effectively.** Review only ever *accumulated* because
  shared-link responses arrived while the couple wasn't looking. Vendor-email
  results are confirmed inline, and direct entry targets a specific guest so no
  matching runs. The one case left — attendance confirmed, companion count blank —
  is a filter on the ledger, not a screen of its own.

Token hashing, constant-time comparison, log masking, and per-wedding rate limits
still apply to what remains (session, invite).

## 2. Login — 네이버 · 카카오 · 구글

Widened from Kakao-only. Server-side session behind an HttpOnly cookie is
unchanged, as is the rule that session lookup reads a token from the request
rather than depending on the cookie.

## 3. `Wedding` carries the contracted 보증인원

Closes the item left open 2026-08-05. The aggregation can now show estimate
against guarantee rather than a bare number.

## 4. Meals are a typed axis the couple configures

Venues differ irreducibly: some hotels support gluten-free, some have a separate
유아식, some are buffet with no distinction at all. **We cannot fix the type list,
so the couple builds it** from their venue contract.

- **`Wedding` carries a configurable meal type list.**
- **A guest's meal counts are per type**, drawn from that list.
- **The default is a single type.** A buffet or single-course couple configures
  nothing, and the model then behaves exactly like the 2026-08-05 "meal is one
  integer on the ledger". Only couples who need types add them — the simple case
  stays simple.
- **A type already assigned to guests cannot be deleted.**

### Dietary needs are meal types, not a separate concept

If the venue supports gluten-free, the couple adds it as a type and it becomes a
counted number the venue receives. If the venue doesn't, that guest eats the
standard meal and there is nothing to record. One concept covers both cases, so
the free-text "dietary note" field considered earlier is not needed.

### The response/ledger asymmetry widens

The vendor email gives "meal yes/no + companion count" and never says whether one
of the party is a child. **Distributing a party across meal types is therefore the
couple's work in the ledger.** The response supplies the total; the couple splits
it. This is the same asymmetry recorded 2026-08-05, now wider — and it is why the
ledger, not the response, is where meal detail lives.

## 5. Accessibility needs are a guest attribute

Wheelchair access and things like it belong to the **person**, not to the meal.
`Guest` gets a free-text 배려사항 field.

Rationale (founder, 2026-08-06): it stays useful when seat assignment lands, which
a meal-side field would not. Free text rather than structured flags because seating
is not designed yet — fixing a taxonomy now would be inventing requirements. Revisit
once real usage shows what couples actually write there.

## 6. Headcount, revised

    식대 인원 = Σ 하객별·종류별 ( 확정 식사 인원이 있으면 그 값, 없으면 예상값 )

Total on top, per-type breakdown below, shown against the contracted 보증인원.
Everything else from 2026-08-05 stands unchanged — no recommended number, no
statistical adjustment of non-responders, no floor/ceiling band.

## Still open

Most of these were closed later the same day — see
[2026-08-06-design-ledger-and-import.md](2026-08-06-design-ledger-and-import.md),
which also corrects the "review queue disappears" claim above: matching still runs
in v1 for vendor-email paste **and for CSV import**, it just never queues.

- [ ] **Screen and flow design** — still the only blocker before building.
- [x] Whether the same person arriving via a second login provider is merged by
      email or kept as a separate account.
- [ ] Whether 유아식 counts toward the venue's 보증인원 (likely venue-dependent).
- [ ] When the couple configures meal types — onboarding, or on demand.
- [x] Whether `Guest.group` is free text or a fixed list.
- [x] Two 2026-08-05 defaults still unconfirmed: expected attendance defaults to
      attending, and expected meal count defaults to expected party size.
