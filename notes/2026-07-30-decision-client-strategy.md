# Decision — client strategy: future app, and PC/mobile split (2026-07-30)

Follows the stack decision
([2026-07-30-decision-tech-stack.md](2026-07-30-decision-tech-stack.md)).
Web is the platform for the foreseeable future; this records what we do
now so that a native app stays possible later, and how the web handles PC
and mobile.

## Scope of the "app someday" question

**The guest RSVP page is web forever.** Someone who taps an invitation
link and answers in 30 seconds will never install an app. So the native-app
question applies to the **couple app only** — which shrinks the problem a
lot.

The stack already leans this way: Spring renders no HTML and serves JSON
only, so a native client is an additional consumer, not a rewrite.

## What we do now to keep the door open (two rules)

### 1. The session must not be tied to cookies

The essence of the chosen auth is that **the server holds the session and
the client holds only an opaque identifier**; the cookie is merely the
transport. Native apps handle cookies awkwardly and would want an
`Authorization` header instead.

So: session lookup is written as "extract the token from the request", not
"read the cookie". Adding a native transport later is then a few lines, not
a redesign. (This is also where the earlier rejection of JWT pays off — we
get app-friendliness without giving up revocation.)

### 2. Computation belongs to the server, never the client

The API returns **conclusions, not raw rows to compute over**: the
meal-guarantee headcount, response rate, non-responder determination,
matching state. If React computes them, a native app would have to
reimplement the same logic and the two clients could then disagree about
the numbers.

This is the same rule the "numbers must never be wrong" value already
demands, so it costs nothing extra — it just also happens to be what keeps
a second client cheap.

## What we deliberately do NOT do now

- Do not choose React Native / Flutter.
- Do not build a cross-platform design system in advance.
- Do not adopt GraphQL "for future client flexibility".

All of these pay today for a future that may not arrive, against
깔끔하되 핵심은 다 있게. The two rules above are sufficient.

**Expected trigger for revisiting**: day-of notifications. Web push is
effectively unusable on iOS (it requires add-to-home-screen), so if
day-of alerts and checklists get built, that is the moment to reopen the
native question. Nothing in v1 (the guest ledger) needs it.

## PC / mobile: different primary device per screen

Not "a PC site and a mobile site", and not naive responsive scaling
either — a squeezed-down ledger table serves neither device. One codebase,
but screen structure differs by device where the usage context differs.

| Screen | Primary device | Approach |
|---|---|---|
| Guest registration, bulk entry, Excel import | PC | Sit-down work. Design PC-first; mobile is view-oriented and reduced. |
| Aggregation (headcount, response rate, non-responders) | Both | PC gets tables and detail; mobile answers "how many right now" at a glance — and mobile is where it is opened most often. |
| Guest RSVP page | Mobile | Effectively mobile-only. Mobile-first; on desktop it only has to not look broken. |

Implementation: styling differences go through Tailwind breakpoints;
**only where the structure genuinely differs do we split the component**
(e.g. ledger as a table on PC, a card list on mobile) while sharing one
route and one data-fetching layer. Two leaves, not two apps.
