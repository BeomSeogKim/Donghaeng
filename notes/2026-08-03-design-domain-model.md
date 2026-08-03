# Design — guest ledger domain model (2026-08-03)

Builds the domain model on top of the decisions in
[2026-07-26-mvp-v1-requirements.md](2026-07-26-mvp-v1-requirements.md) and
[2026-07-30-design-guest-ledger-hard-spots.md](2026-07-30-design-guest-ledger-hard-spots.md).
Model level only — no schema, no API shapes yet.

## Skeleton

    User ──< Membership >── Wedding
                              ├──< Guest ──< GuestLink
                              ├──< RsvpResponse ──o ResponseMatch >── Guest
                              ├──o SharedLink
                              └──< EmailIngest ── VendorTemplate

`Wedding` is the top-level unit (decided 2026-07-30). Everything else
belongs to a wedding.

## 1. The ledger is the current value; responses are evidence

Two layers, and the split is what keeps the numbers honest:

- **`Guest` holds the current value.** The couple entering guests by hand
  is the primary path — our own RSVP and vendor email are inflow channels
  that fill the ledger, not replacements for it.
- **`RsvpResponse` is immutable evidence.** It records what someone
  actually submitted and never changes.

### Expected and confirmed are separate slots, per field

Section C of the MVP requirements already commits to "expected vs.
confirmed counts", so the ledger must represent both. **Attendance,
companion count, and meal each carry their own expected slot and confirmed
slot.**

Per-field granularity is load-bearing, not incidental: the `needs_review`
aggregation pool is "attendance confirmed, but companion count left
blank". That pool is inexpressible if confirmed is one atomic slot on the
Guest.

- **Couple input writes the expected slot only.** Freely editable at any
  time; responses never overwrite it.
- **Responses write the confirmed slot only.** From any origin.
- Confirmed layered over expected is the normal flow, not a conflict.

### Blank is not zero

A response that leaves companion count blank means *unknown*, not zero.
`companion_count` is nullable on `RsvpResponse` and the aggregation treats
blank as "needs review", never as 0. Guests routinely skip these fields —
the model must survive that rather than silently guessing. Guests can
re-submit through the same link to fill them in later, and the couple can
always edit the ledger directly.

### Confirmed values always trace to a response

The 2026-07-30 record requires every number in the aggregation screen to
trace back to a raw response. So a confirmation the couple heard by phone
is entered as an `RsvpResponse` with `origin=manual` rather than written
straight onto the Guest. Manual entry has to exist anyway (unsupported
vendor templates fall back to it), so this costs nothing and keeps the
standing constraint intact.

### Conflict rule

A new confirmed value that disagrees with an existing confirmed value goes
to `needs_review`. **Never a silent overwrite** — a number that changes
without anyone seeing it fails 정직함·믿음직함. Under the expected/confirmed
split this case is rare (confirmed-vs-confirmed only), so the rule is cheap
to hold.

## 2. Matching: the same-person call lives only in the pipeline

**Decided: no `Respondent` entity.** Responses are not pre-grouped by
identity. Responses matched to the same `Guest` are what "same person"
means; the "latest response wins" rule applies within one guest's response
history. Two unmatched responses are never assumed to be the same person.

Rationale: it keeps the existing "2+ candidates → `needs_review`, never
guess" principle as the single place identity is decided. Pre-grouping by
name + side (+ phone4) would add a second place to misjudge 동명이인,
especially since phone4 is optional and often absent.

### Duplicate defense per channel

| Channel | Where the guest is pinned | Duplicate defense |
|---|---|---|
| Shared link | server-side matching (silent) | same-device confirm + `duplicate_suspect` review |
| Per-guest link | the token is the identity | latest-wins automatically |
| Vendor email paste | pipeline proposes, couple confirms on the spot | body hash + existing-response warning |

**Warnings only ever fire inside the trust boundary.** On the public RSVP
page, telling a guest "a response like this already exists" would turn the
page into an enumeration oracle — name+side probing reveals who responded,
and varying phone4 reveals their digits. That contradicts the enumeration
safety requirement. So:

- **Guest-visible**: only a same-device re-submit confirm, driven by a
  local device marker with no server lookup. Every submission still ends on
  the identical confirmation screen.
- **Couple-visible**: the server silently detects collisions (same side +
  same name, and phone4 compared only when both sides have it) and raises
  `duplicate_suspect` into the couple's review queue.

`ResponseMatch` carries a `duplicate_of_response_id` reference for that
case. The couple sees both raw responses side by side and decides "same
person → latest only" or "different people → keep both".

## 3. Vendor email channel

RSVP notifications from third-party 모청 vendors arrive **by email**
(confirmed with the founder 2026-08-03). A real sample carries: 하객 구분
(side), 참석 여부, 식사 여부, 성함, 추가 동행 인원, 확인 연락처 (last 4
digits), 추가 전달 내용.

That maps almost 1:1 onto `RsvpResponse`, which simplifies the flow:

    paste → body-hash check → identify vendor → parse by template
          → store response + raw text → matching pipeline proposes
          → couple confirms inline → existing-response check → confirmed

- The body-hash check catches the couple both pasting the same
  notification — it compares pasted text, not email addresses (the RSVP
  data contains none).
- The **same matching pipeline** as the shared link runs here (side → name
  → phone4). The only difference between channels is *when* review happens:
  shared-link results queue up, paste results are resolved inline while the
  couple is already looking at the screen. Showing candidates here is safe
  because the couple already sees the whole ledger.
- Unsupported template → inquiry filed, raw text stays on screen, manual
  entry offered (unchanged from 2026-07-30).
- The couple's side is irrelevant to attribution — the bride may enter a
  groom-side guest's RSVP. `created_by` covers who did it.

Model consequences: `RsvpResponse` gains an optional `message` field (추가
전달 내용), `companion_count` is nullable, and the parser normalizes the
contact field to the last 4 digits so every channel compares on the same
phone4 key.

## 4. Provisional guests

An `unmatched` response creates a **real `Guest`**, not a separate kind of
object. The expected/confirmed split already distinguishes it: expected
slots empty, confirmed slots filled from the response — that combination
*is* "nobody predicted this person, but they answered". One lifecycle flag
(`provisional` → `active`) carries the couple's acknowledgement.

Three ways the couple resolves it: accept as a real guest (state flips),
merge into an existing ledger guest, or dismiss.

**Merging is lossless, and that is a property of the model rather than a
feature.** A provisional guest holds no couple-authored data — its expected
slots are empty by construction — so discarding it destroys nothing. The
response survives untouched and only the `ResponseMatch` link moves to the
target guest. This is what makes the "matching is reversible" promise from
2026-07-30 real instead of aspirational.

Dismissal keeps the response (all responses are kept, per 2026-07-30); it
is a match state, never a delete.

**Provisional guests count in the aggregation.** Someone who answered
"참석" is coming whether or not they were on the list, and excluding them
would undercount the meal guarantee — which is money. They are counted and
visibly flagged, not counted-later.

## Withdrawn during this session

Recorded so they don't get re-proposed:

- **"Confirmed values come only from responses, and `Guest` holds no
  confirmed fields"** — superseded. It assumed RSVP was the primary path;
  couple entry is.
- **Renaming the channel to "vendor notice paste" (`PastedNotice`)** —
  withdrawn. The channel is email; `EmailIngest` stands, and the
  forwarding-address increment stays valid as a post-v1 item.

## Still open

- [ ] Aggregation: the exact 식대 보증 인원 definition over the three pools
      (confirmed / needs-review / expected).
- [ ] Whether one meal answer covers the whole party, or each companion
      needs their own. v1 assumes the former; the couple fixes exceptions
      from the review queue.
- [ ] Screen and flow design.
