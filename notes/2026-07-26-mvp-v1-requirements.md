# MVP v1 requirements — guest ledger (2026-07-26)

Agreed breakdown of what must exist to realize the core value. Scope:
guest list + RSVP collection + headcount estimation (per
[2026-07-26-decision-core-scope.md](2026-07-26-decision-core-scope.md)).

## A. Guest list (couple-facing)

- Add/edit guests: name, side (신랑측/신부측), group (family, relatives,
  friends, work, …), contact, companion count.
- **Side is structural** — meal counts, seating, and 축의금 all pivot on
  it; required from day one.
- Low entry barrier: couples already have lists in Excel/contacts, so
  either Excel/CSV import or a very fast bulk-entry flow is required for
  "better than Excel" to hold.
- Couple co-use: bride and groom each manage their side, sharing one
  ledger (two accounts, one ledger — a known design problem).

## B. RSVP collection (guest-facing, thin)

Two intake channels:

1. **Own RSVP link** — couple creates a link, places it in their mobile
   invitation; guest opens it and answers attendance / companion count /
   meal. **No guest signup, ever** — target: respond within 30 seconds of
   opening. Mobile-first; the guest page is the brand's first impression,
   so clean and trustworthy.
2. **Email parsing from third-party 모청 vendors** (founder addition,
   2026-07-26) — RSVPs that arrive by email from the couple's existing
   mobile-invitation vendor get parsed into the ledger. Template-based:
   known vendor templates parse directly; unknown templates → the couple
   files an inquiry and we build the template then. Covers couples already
   committed to another 모청. Ingestion mechanism (copy-paste vs. dedicated
   forwarding address vs. mailbox OAuth) is a design-time decision; lean
   light (paste or forward) — mailbox OAuth is heavy against the
   trustworthiness value.

- Response↔ledger matching: responses from known guests attach to their
  entry; unknown respondents enter as new pending guests for the couple to
  confirm. Same-name matching (동명이인) is a known hard spot needing
  design.

## C. Headcount aggregation (where Excel loses)

- Expected vs. confirmed counts; by side and group; meal counts — the
  screen must ultimately answer the **meal-guarantee headcount (식대 보증
  인원)** decision.
- Response rate + non-responder list → "who do I need to nudge" at a
  glance.

## D. Non-functional requirements from the product values

- *정직함·믿음직함*: guest contacts are sensitive data — security and
  privacy in the design from the start. **Numbers must never be wrong** —
  the meal-guarantee count is money; aggregation accuracy is trust, not a
  feature.
- *깔끔하되 핵심은 다*: nothing outside this list ships in v1. Seating,
  축의금, checklists come after the ledger is solid.

## Known hard spots (flagged for design)

Resolved 2026-07-30 — see
[2026-07-30-design-guest-ledger-hard-spots.md](2026-07-30-design-guest-ledger-hard-spots.md).

1. RSVP response ↔ ledger matching (동명이인, guests not on the list).
2. Couple co-use structure (two accounts, one ledger).
3. Email template registry + parse pipeline for vendor RSVPs.
