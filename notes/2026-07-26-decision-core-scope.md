# Decision — vision, core scope, and product values (2026-07-26)

Agreed during the kickoff alignment conversation, following the founder
interview ([2026-07-26-founder-interview.md](2026-07-26-founder-interview.md)).

## Vision

Donghaeng is a companion across the **whole wedding journey** — from the
start of preparation, through the day itself, and after. This supersedes
the original README positioning of "day-of only." Day-of coordination
remains part of the journey, not the whole product.

Long-term, essentially all wedding-prep features are in scope — but they
are built incrementally, in an order that protects quality and accuracy.
Breadth never comes at the cost of correctness.

## Core feature axis: the guest ledger

The agreed core — 하객 관리 (guest management), 좌석 관리 (seating),
축의금 관리 (cash-gift management) — is one object, not three features:
a **guest ledger** that gains columns over the journey.

- Preparation: guest list, RSVP, headcount estimation (replaces the Excel
  workflow that caused the most pain in the founder interview).
- Day-of: seat assignment (hotel/assigned-seating weddings), reception.
- After: who gave what, thank-you follow-ups, and a durable record the
  couple consults for years (Korean 축의금 reciprocity culture).

The 축의금 dimension extends product life beyond the wedding day and
removes the "used once, then deleted" weakness of a day-of-only tool.

## Product values

1. **정직함 · 믿음직함** (honest, trustworthy) — befitting a premium
   wedding service. Handling money data makes this a concrete engineering
   requirement (data security, privacy), not just brand tone.
2. **깔끔하되 핵심은 다 있게** (clean, yet nothing essential missing) —
   fewer things, each complete and polished.

## MVP v1 boundary (decided 2026-07-26)

**Guest list + RSVP collection + headcount estimation.** The preparation-
phase ledger only; seating and 축의금 come in later increments. RSVP
collection implies a thin guest-facing response page (a link the couple
places in their mobile invitation) — guests become a second, minimal user
type from v1.

## Still open

- [ ] Success criteria: deliberately deferred — to be defined after the MVP
      is built (founder decision 2026-07-26). Focus goes to what must be
      built to realize the core value.
- [x] Tech stack — decided 2026-07-30, see
      [2026-07-30-decision-tech-stack.md](2026-07-30-decision-tech-stack.md).
