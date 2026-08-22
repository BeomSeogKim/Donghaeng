# Decision — v1은 애플리케이션 레이트 리밋을 싣지 않는다 (2026-08-22)

Closes `#155`, which was a policy vacuum rather than a defect: `api/AGENTS.md`
said what a limit must look like, nothing implemented one, and no document said
whether that was a decision or an omission. **A rule that states only a shape
reads as covered.**

**The call: v1 ships no application-level rate limit. The only limits in v1 are
the edge rules already named in
`2026-08-17-decision-pre-auth-rate-limit-and-session-retention.md`.**

## What that leaves standing

The 08-17 record put the login path at the edge —
`/oauth2/authorization/*` and `/login/oauth2/code/*` named explicitly, beside
the public RSVP POST — for a reason that also explains the silence here: **the
edge is the only place an IP is worth anything**, since Korean carrier NAT puts
real guests behind shared addresses and an app-level IP bucket blocks people we
invited. Everything the edge covers is unauthenticated. Everything left is not.

## Why nothing else in v1 needs one

**Every remaining surface is behind a session and scoped to one wedding.** The
헤드카운트 endpoint is the first in this product whose per-request cost is an
aggregate rather than a point read, and `ix_guest_wedding` matches its predicate
exactly: an index scan over one wedding's 200–800 rows. A seated member hammering
their own 인원수 is not better leverage than hammering the `GET .../guests` that
has been deployed for days.

**The one surface where a limit would ordinarily be the answer is 초대 수락**, a
bearer-credential endpoint that a guesser could in principle grind. Two things
stand in for the limit and they are both already there: it **refuses before it
looks at the body**, so an anonymous grinder never starts, and the token is a
**256-bit verifier compared as a hash**. Entropy is the control; a counter would
be decoration beside it.

## What flips this, named so the next reader does not have to infer

1. **Our own RSVP links ship** (`post-v1`). That is an unauthenticated POST per
   guest — the edge rule already anticipates it, and it is the first traffic
   that is neither logged-in nor bounded by a seat.
2. **An endpoint stops costing one wedding's rows** — an export, a cross-wedding
   read, anything whose work is not bounded by `wedding_id`.
3. **The 4xx log line `#65` added shows an actual attempt.** The security
   record's ask was 알아챌 능력, and that line is what makes this decision
   revisable on evidence rather than on nerves.

**The shape rule in `api/AGENTS.md` is unchanged and still binds** — per wedding,
per link token, never IP-only. It describes what the *first* limit must look
like, and this record is why there is not one yet.
