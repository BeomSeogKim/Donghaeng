# Design decisions — the three MVP v1 hard spots (2026-07-30)

Resolves the "known hard spots" flagged in
[2026-07-26-mvp-v1-requirements.md](2026-07-26-mvp-v1-requirements.md):
RSVP↔ledger matching, couple co-use structure, and the vendor-email parse
pipeline. Data-model level only — no tech stack is implied or chosen here.

## Spine: two intake channels, one response model, one matching pipeline

Both RSVP channels (own link, vendor email) produce the same
`RsvpResponse` record and go through the same matching pipeline. Nothing
downstream needs to know where a response came from, beyond an origin
field kept for auditability.

    own link ─┐
              ├─→ RsvpResponse ─→ matching ─→ Guest (ledger) ─→ aggregation
    vendor ───┘
    email

## 1. RSVP response ↔ ledger matching

> **The object split below is out of v1 as of 2026-08-06** — see
> [2026-08-06-decision-drop-response-model.md](2026-08-06-decision-drop-response-model.md).
> The **matching rules** in this section are fully in force; only the persistence of
> responses and match states is deferred, along with the links that justified it.

**Responses and ledger entries are separate objects.** An `RsvpResponse`
is an immutable record of what someone actually submitted. The link to a
`Guest` is a separate, reversible thing carrying a state:
`auto_matched` / `needs_review` / `unmatched`. There is no silent merge —
every number in the aggregation screen can be traced back to a raw
response.

### Guest-facing input

- **Name + side (신랑측/신부측)** — required. Side is one tap and it halves
  the candidate space.
- **Last 4 digits of phone number** — optional, labelled as being for
  telling same-name guests apart. Kept optional so the 30-second response
  target holds.
- Plus the answer itself: attendance, companion count, meal.

### Matching rules

1. Narrow candidates by side, compare on normalized name (whitespace,
   spacing variants), then on phone-4 when both sides have it.
2. **0 candidates** → `unmatched`. Enters the ledger as a pending guest
   for the couple to confirm — never dropped.
3. **1 candidate** → `auto_matched`. Shown as linked, and the couple can
   unlink at any time.
4. **2+ candidates** → `needs_review`. **We never guess.** The couple sees
   the raw response next to the candidates and picks one, or creates a new
   guest.

### Enumeration safety (privacy requirement, not polish)

The public RSVP page must **never reveal whether a name is on the list**.
No "found you" feedback, no differing error states — every submission ends
on the same confirmation screen. Otherwise the page becomes a guest-list
enumeration oracle, which is disqualifying under 정직함·믿음직함. All
matching happens server-side and is visible only to the couple.

### Duplicate responses

All responses are kept. The latest response from the same person is the
effective value; earlier ones remain visible as history.

### Two link types (decided 2026-07-30, both deferred out of v1 on 2026-08-06)

> Neither link ships in v1 — see
> [2026-08-06-decision-v1-scope-and-meals.md](2026-08-06-decision-v1-scope-and-meals.md).
> The design below stands for when they return; the matching pipeline it feeds
> runs in v1 only for vendor-email responses, resolved inline rather than queued.

- **Shared link** — required. One link the couple drops into their mobile
  invitation. Responses go through the matching rules above.
- **Per-guest link** — also in v1. For guests already on the ledger, the
  couple can send an individually tokenized link (e.g. by KakaoTalk). The
  token identifies the guest exactly, so matching is unambiguous and the
  동명이인 problem disappears on that path. Cheap to build, removes the
  hardest case for the guests that matter most.

Per-guest link tokens are unguessable and single-purpose (they authorize
responding as that guest, nothing else).

## 2. Couple co-use (two accounts, one ledger)

**The top-level unit is the Wedding, not the user.** Guests and the ledger
belong to a wedding; users participate through a membership.

- `Wedding` — date, venue, the couple's names.
- `Membership` — user + wedding + side (신랑/신부).
- `Guest.side` is a structural field of the guest and is **independent of
  who entered it** — the groom may register a bride-side guest.

**Access is fully shared (decided 2026-07-30).** Both partners see and
edit everything; the membership's side acts only as the default view
filter. Rationale: the meal-guarantee headcount is a combined decision, so
both need full visibility, and a permission wall adds complexity without
buying trust. Revisit when 축의금 lands — inter-family privacy may create
a real requirement there, and that is the right time to judge it.

Joining: one partner creates the wedding and invites the other with an
invite link. Parents and other helpers are out of scope for v1.

Every guest mutation records who and when (`created_by` / `updated_by`).
Cheap, and it is what makes "who changed this number?" answerable.

## 3. Vendor email parse pipeline

    ingest → store raw → identify vendor → parse by template
           → RsvpResponse → (joins the matching pipeline above)

### Ingestion: paste-first for v1 (decided 2026-07-30)

The couple pastes the email body into the app and it is parsed on the
spot. Zero inbound-mail infrastructure, and the couple sees the parse
result immediately, which is the honest failure mode.

Accepted trade-off: pasting is still per-email manual work, so the double
bookkeeping is reduced (no re-typing of fields) but not eliminated. A
dedicated forwarding address that makes ingestion automatic is the
follow-up increment once the parse layer has proven itself on real vendor
templates.

### Parsing

- The raw pasted text is stored as received. Parsed fields are derived
  data, and the original stays available for verification.
- Vendors are identified by body signature (and sender headers when
  present) against a versioned, declarative template registry.
- **Failure is safe, never a guess.** An unrecognized or changed template
  produces `unsupported` — the couple is told the vendor isn't supported
  yet, an inquiry is filed, the raw text stays on screen, and manual entry
  is offered. A low-confidence parse becomes `needs_review` rather than a
  silent number.
- When a vendor changes its email format, this design turns that into a
  visible failure instead of corrupted counts.

### Retention

Raw pasted text is kept only until the derived response is confirmed, plus
a bounded window, then deleted — personal-data minimization.

## Still open

- [ ] Tech stack — next decision.
- [ ] Screen/flow design for the ledger, aggregation, and guest RSVP page.
- [ ] Forwarding-address ingestion (post-v1 increment).
- [ ] Inter-family privacy on shared access — revisit at 축의금.
