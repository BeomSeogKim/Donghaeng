# Decision — the pre-auth limit is the edge's, and a dead session keeps 90 days (2026-08-17)

Closes `#98` and `#91`. Both came out of the `#37` audit, both are about what
happens on the login path when nobody is authenticated yet, and both stayed
open until the login work was finished enough to answer them honestly.

## `#98` — the limit belongs at the edge, named

`/oauth2/authorization/google` is unauthenticated and creates a container
session per cookie-less request to hold `state`, the PKCE verifier and the
nonce. Nothing bounds it. The standing rule is **per wedding, and per link
token once links exist — never IP-only**, and a person who has not logged in
has neither.

**The call: the edge rate-limit rules name `/oauth2/authorization/*` and
`/login/oauth2/code/*` explicitly, beside the public RSVP POST.**

The reason this is not an application concern is the same reason the standing
rule forbids IP limits *in the application*: Korean carrier NAT puts real
guests behind shared addresses, so an app-level IP bucket blocks people we
invited. **The edge is where an IP is worth something** — it sees the whole
front, can distinguish a burst from a crowd, and drops traffic before it costs
us a session object. Writing the two paths into the rule is the entire work;
the mechanism already exists for the RSVP POST.

`#65` widened this while it was open: the *callback* path is outside the
standing rule too, not only the authorization path. Both are named above.

**The container session gets a short TTL**, and
`server.servlet.session.timeout` stops being forbidden. The ban was written
when `JSESSIONID` held nothing — our own session expiry is enforced by
`SessionService` and still is, so the ban's reasoning is untouched for *our*
sessions. What changed is that the container session now holds a live
authorization request, and its retention is Tomcat's 30-minute default times
the request rate. `ProfileConfigurationTest`'s `describedAs` has to be rewritten
to say what it now forbids: **not a container TTL, but a container TTL standing
in for ours.**

**What this does not buy:** the edge cannot stop a determined single client
from opening authorization requests at a modest rate. It bounds the accident
and the cheap flood, which is what the security record's "알아챌 능력" asks
for, and `#65`'s 4xx log line is what makes the attempt visible.

## `#91` — an expired session row keeps 90 days, then goes

Nothing deletes expired or revoked `user_session` rows. They accumulate
forever.

**The call: delete 90 days after the row's expiry.**

The tension is real and the answer is a number, not a principle. An expired
session row is **not testimony** — unlike `GuestChange` (`#34`), which records
what a person did to the ledger, a dead session records only that a
already-known fact stopped being true. Keeping it forever is hoarding.

But **it is the only source that answers "who logged in, when, from where"**,
and that question only ever gets asked while investigating an account
takeover. So the retention is set by how long it plausibly takes to notice one:
a couple opens this a few times a month (`2026-08-12-decision-session-lifetimes.md`),
so a suspicious login can sit unnoticed for weeks. 90 days after expiry clears
that span with room, and still bounds the table.

**It is measured from expiry, not from creation.** A session with a 180-day
absolute lifetime that ran its full course is 270 days old when it goes, and
that is correct — the window opens when the session dies, not when it starts.

**Revoked rows keep the same 90 days.** A revocation is the interesting case,
not the boring one: logging out everywhere is what someone does *after*
noticing something wrong, and deleting those sooner would delete exactly the
evidence the investigation wants.

## What this does not decide

- **The edge's numbers** — the actual limits belong with the deploy
  configuration, `#26` and `#51`.
- **How the deletion runs.** A scheduled job is the obvious shape but this
  repo has no scheduler yet, and `#79` (the vendor-email purge) wants the same
  thing. Whichever lands first should build it so the second one only adds a
  query.
- **`GuestChange` retention** — `#34`, and it is a different question: an
  audit log deleted on a timer is not an audit log.

Refs `#98`, `#91`, `#26`, `#34`, `#65`, `#79`,
`notes/2026-07-30-decision-network-security.md`,
`notes/2026-08-12-decision-session-lifetimes.md`
