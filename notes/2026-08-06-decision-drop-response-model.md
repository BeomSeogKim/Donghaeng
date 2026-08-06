# Decision — drop the response model from v1 (2026-08-06)

Third record of the day, and a reversal of a foundational one. Removes
`RsvpResponse` and `ResponseMatch` from v1 and replaces the traceability they were
carrying with a `GuestChange` audit log.

Reverses the core of
[2026-07-30-design-guest-ledger-hard-spots.md](2026-07-30-design-guest-ledger-hard-spots.md)
§1 and [2026-08-03-design-domain-model.md](2026-08-03-design-domain-model.md) §1–2
— **for v1 only.** The condition for bringing them back is stated below.

## What prompted it

Founder, reading the ERD: what matters to the couple is whether a guest is coming,
not which route the answer arrived by.

## The invariant was already broken

`RsvpResponse` existed to make one rule true: *every confirmed number traces back to
a raw response*. That rule stopped holding earlier the same day. Splitting meals
into types made **distributing a party across types the couple's work in the
ledger**, and those confirmed numbers have no response behind them. We were carrying
two tables and a state machine for an invariant the design had already abandoned.

## Each justification, re-checked against v1

| Original justification | Holds in v1? |
|---|---|
| Separate expected from confirmed | **No.** `Guest`'s own expected/confirmed slots already do this. |
| Trace numbers to their origin | **No.** For couple entry the "origin" is a tap; the record says nothing that `updated_by` / `updated_at` doesn't. |
| Preserve the vendor email's raw text | **No.** `EmailIngest.raw_text` does that. It was never the response's job. |
| Keep change history | **Yes** — the only real loss, and the audit log covers it better. |
| Reserve the seat for RSVP links | **Weak.** Adding the tables later is additive, with no backfill: data from before the links legitimately has no responses. |

## The actual principle

A response model exists for **writes that happen while nobody is watching** — a
guest submitting at midnight, 동명이인 to disambiguate later, a queue the couple
works through the next morning.

**v1 has no such write.** Couple entry, vendor-email paste, and import conflict
resolution all happen while the couple is looking at the screen. Keeping evidence
earns its cost when the couple wasn't there, and in v1 they always are.

That is also the exact condition for reversing this: **the RSVP links reintroduce
unwatched writes, and the response model comes back with them.**

## What v1 has instead

### `GuestChange` — a field-level audit log

One row per changed field: the guest, the field, old value, new value, who, when,
and **where the change came from** — `MANUAL` / `VENDOR_EMAIL` / `IMPORT`, with a
nullable FK to the `EmailIngest` or `GuestImport` that caused it.

This *subsumes* what the response model did for traceability, and does it more
completely:

- It covers **every** field, including the meal-type distribution responses never
  reached.
- The source enum carries what `RsvpResponse.origin` carried.
- The ingest FK preserves "the raw email stays available for verification"
  (2026-07-30) — per change rather than per guest.
- One table instead of two, with no state machine.

Guest creation is not logged; `created_by` / `created_at` on `Guest` covers it.

### Matching survives as logic, not as tables

Candidate-finding (side → normalized name → phone) still runs, for vendor-email
parsing and for import conflict detection. Results are consumed on the screen the
couple is already looking at and are never persisted.

**"2+ candidates means we never guess" is unchanged.** It was always a rule about
behaviour, not about storage.

## Cost accepted

The audit log holds old values of personal data — phone numbers among them — which
sits in mild tension with the data-minimisation stance that has raw vendor email
deleted after a bounded window. It deserves a retention policy eventually. Not a v1
blocker, and noted so it isn't discovered later.

## Consequences elsewhere

- `Guest.confirmed_from_ingest_id`, floated an hour earlier as the replacement for
  response traceability, is unnecessary — the audit log's per-change FK is strictly
  better.
- v1 tables: 14 → 13.
- The standing constraint "every intake channel converges on one response model and
  one matching pipeline" becomes **"every intake channel converges on one matching
  pipeline"**. The pipeline was always the load-bearing half.

## Still open

- [ ] **Screen and flow design** — unchanged, still the only blocker.
- [ ] Whether 유아식 counts toward the venue's 보증인원.
- [ ] When the couple configures meal types.
- [ ] Retention policy for `GuestChange`.
