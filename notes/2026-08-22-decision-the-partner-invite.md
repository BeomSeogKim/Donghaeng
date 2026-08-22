# Decision — the partner invite is a hashed one-day credential, and joining is the third unscoped endpoint (2026-08-22)

`#181`, the backend half of `#9`. `2026-08-22-decision-the-invite-link.md` decided
the link's *life* (one day, single use, reissue kills the previous, token in the
fragment and never in a path) and explicitly left four things to this issue: the
accept endpoint's shape, the invite table's columns, what the token's stored form
is, and what a mutation with no aggregate answers. This record is those four, plus
the concurrency design they turned out to need.

**Everything here binds `#182`**, the frontend half. §5 is the part that will
surprise it.

## 1. Two endpoints, and only one of them can be scoped

```
POST /weddings/{weddingId}/invite   → 201 { token, expiresAt }
POST /weddings/join                 → 200 WeddingResponse
```

Issuing is wedding-scoped in the ordinary way: the seat walk decides whether this
caller may invite anybody into this wedding, so a logged-in stranger gets 404 and
never 403. **Accepting cannot be**, because the caller holds no seat yet — that is
what the request is *for*. It is therefore the **third endpoint in the product that
is not scoped to a wedding**, after `POST /weddings` and `GET /weddings`, and the
spec says so where the closed set is stated.

**It lives under `/weddings` on purpose.** `ScopelessWeddingEndpointTest` sweeps
handlers whose path starts with `/weddings` and fails unless an unscoped one is
named in its list with a reason; a path like `/invites/accept` would have escaped
that sweep entirely. That test's own comment warns about exactly this — "falling
outside a gate is not the same as being let through it" — so putting the endpoint
where the gate can see it, and then arguing the exemption in the diff, is the
cheaper of the two honest options. `join` is the verb because the one-wedding rule
is already written as "created or joined": `POST /weddings` and
`POST /weddings/join` are the two ways a person acquires a seat.

**What stands in for the scope is the token**, and the substitution is only sound
because the token is what §2 says it is.

## 2. The token: `<selector>.<verifier>`, hashed, exactly like a session

16 CSPRNG bytes of selector, 32 of verifier, base64url. The row keeps the selector
and a **SHA-256 of the verifier**; the verifier itself is never stored in any form.
A leaked database dump therefore hands over no working invite links, which is the
property that makes a bearer credential affordable at all.

**Why split, rather than one `sha256(token)` column under a unique index.** The
short design is not insecure — it compares hash against hash, and a hash prefix is
no route to a 256-bit preimage — but `api/AGENTS.md` names invite tokens explicitly
under "≥128-bit CSPRNG, stored SHA-256-hashed, **constant-time compared**", and a
btree lookup contains no comparison this application makes. `V2__user_session.sql`
already argued the same point for sessions: splitting the token gives the
comparison a place to live where deleting it turns a test red.
`AcceptInviteContractTest` is what watches it fail.

**`InviteToken` is a sibling of `SessionToken`, not a reuse of it, and that is a
cost taken deliberately.** `SessionToken` is internal to `auth/session`, a different
domain that publishes no token primitive as a cross-domain contract; reaching into
it — or hoisting it into a shared package — would have refactored the session token
inside a change about invites, in the one file a security review least wants
restructured for somebody else's feature. The residual is named rather than hidden:
**the two must change together**, and the KDoc on each says so. If a third token
kind arrives (the RSVP links, `#27`), that is the moment to extract one primitive,
with three call sites to justify it instead of two.

## 3. The table: `wedding_invite`, keyed on the seat

`V1`'s header left this table out in as many words — "mechanism not designed, so do
not guess the columns". The mechanism is designed, so `V4__wedding_invite.sql`
writes it. The file argues each column; two choices are worth repeating here.

**No `wedding_id`.** The standing rule is that every wedding-scoped aggregate root
carries one, and the distinction the 2026-08-11 record makes is checkable: an
*integrity* `wedding_id` appears in a composite FK to a parent's `(id, wedding_id)`,
a *root's* does not. What earned `guest_meal_count` its copy was a `meal_type_id`
arriving in a request body, which bypasses `CurrentWedding`. **No id here ever
arrives from a request**: the issuing endpoint is wedding-scoped and the server
picks the seat off the resolved wedding, and acceptance addresses the row by token.
So a `wedding_id` would be a second statement of what `wedding_party.wedding_id`
already says, needing a composite FK to keep the two honest, in service of a query
that does not exist — this table is only ever read by `selector` or by `seat_id`.

**Three columns end an invite's life and they are not interchangeable**:
`expires_at` (it went stale unopened), `accepted_at` (it was spent), `revoked_at`
(재발급 replaced it). `ux_wedding_invite_live` is partial on the last two, so at
most one live invite per seat is *unrepresentable* rather than merely intended. An
expired row still occupies the slot, which is correct: reissue revokes whatever it
finds, so the statement that needs the slot is the one that frees it.

There is no `deleted_at`, and `2026-08-10-decision-soft-delete.md` does not apply:
nothing here is a row a user deletes. This is `user_session.revoked_at`.

**The one-day cap is not a CHECK.** `expires_at > issued_at` is an invariant under
every reading and belongs in the schema; "at most one day" is product policy. And
the cost of getting that distinction wrong is not "an `ALTER` some day" — it is
worse and worth stating exactly: **a CHECK of `expires_at <= issued_at + interval
'1 day'` would mean that LENGTHENING the lifetime breaks every INSERT between the
deploy and the `ALTER`.** The app would ship a two-day expiry, the database would
refuse it, and the failure would land as a masked 500 on the one write that hands a
couple their link. A constraint that turns a policy change into an outage is a
constraint that has stopped protecting anything. It is not a
`@ConfigurationProperties` either — the environment outranks every yml, so
`DONGHAENG_INVITE_LIFETIME=30d` in a deploy platform would extend a bearer
credential thirtyfold with the whole suite green. It is a `val` in the service,
changed by a code review.

## 4. Concurrency: one lock order, and the seat is the lock

Four races, one design.

**The seat's row lock is taken by both paths before either writes an invite.**
Issue takes it, then revokes and inserts; accept takes it after reading the invite,
then consumes and claims. One global order — advisory-on-user → seat row → invite
write — so there is no cycle to deadlock on.

- **Two people open the same link.** Both seats exist from the moment the wedding
  does (`2026-08-22-decision-the-couples-two-seats.md` §2), so this is a **lost
  update**, not a duplicate membership: without the lock both read an empty seat and
  both write their own name into it, and the loser is silently signed into a ledger
  whose row carries the winner's name. Under `FOR UPDATE` the loser waits, Postgres
  re-evaluates against the committed row, and it is refused 409.
- **Two 재발급 taps.** The loser waits, then revokes the winner's token and mints its
  own: both 201, last tap wins, which is what "reissue kills the previous" means.
- **A token killed between the read and the write.** The consume is a conditional
  UPDATE (`where accepted_at is null and revoked_at is null`) and a rowcount of 0
  answers exactly what a token that never existed answers. The lock is the plan, the
  rowcount and the unique index are the backstops — the same division `ux_party_user`
  has with `claimSoleSeat`.
- **The caller already has a wedding.** `WeddingService.claimSoleSeat` runs
  **first**, from the same bean, with the `MANDATORY` propagation
  `2026-08-21-decision-one-wedding-per-person.md` §3 wrote for this caller. Running
  it before the token is looked at is load-bearing: somebody tapping a link they
  cannot use must not spend their partner's only invite.

## 5. What binds `#182`, in the order it will meet them

**Read this as the screen's requirements, not as trivia.** Four of these were added
after `#186`'s review pointed out that the list described the token and not the
screens — the first one below reshapes 수락 more than anything else here.

1. **수락 is a FORM, not a button.** `POST /weddings/join` requires a `name`, and it
   is the accepting person's **own**: nobody types anybody else's name, which is the
   same rule that took the partner's name out of `POST /weddings`. The partner
   arrives from a KakaoTalk link, signs in, and then has to type who they are before
   anything is written. Validation is `POST /weddings`' exactly — non-blank, ≤100
   characters, measured before the server trims — so one client-side rule covers both
   screens.
2. **The token is published once and can never be read back.** Only the hash is
   stored. There is no endpoint that returns an existing link and there will not be
   one, so 설정 cannot show "the link you made yesterday" — the only affordance is
   재발급. A screen designed around re-displaying a link cannot be built against
   this API.
3. **`expiresAt` is what the UI renders**, not a duration the client computes — and
   it is **memory-only**, which is the consequence of §5.2 that is easy to miss. No
   endpoint reports whether a live invite exists or when it expires, so a reload
   cannot render "8시간 후 만료" either. After a refresh the screen knows nothing
   about the link that was made; that is the API's shape and not an omission to work
   around.
4. **What decides whether 재발급 is offered at all is `seats[].name == null`** on any
   `WeddingResponse` — the only signal the API gives. A seat with a name has a person
   in it, so there is nothing to invite. `PARTNER_ALREADY_JOINED` is what a **stale**
   tab gets; this is how a fresh one knows not to ask.
5. **The link is `https://<app>/invite#t=<token>`, assembled by `web/`.** The API
   does not know the frontend's origin and must not: the token belongs in the
   fragment, the only part of a URL never sent to a server.
6. **Two 404 codes, and they are not one branch.** `INVITE_EXPIRED` says "ask for a
   new one"; `INVITE_NOT_FOUND` covers everything else and says nothing more. The
   distinction is safe because it is only ever told to someone presenting a token
   that really was ours — a guesser gets `INVITE_NOT_FOUND` for a right selector
   with a wrong verifier, identical in every member to what nonsense gets.
7. **Two 409 codes, and they are not one branch either.** `ALREADY_IN_A_WEDDING`
   means "you already have a ledger — open that one" and the recovery is
   `GET /weddings`; `PARTNER_ALREADY_JOINED` means "the seat is gone" and there is no
   recovery to offer. Same status, opposite copy, and only `code` tells them apart.
8. **The accept action sits in front of 웨딩 만들기.** A signed-out partner who
   lands on the root with an empty `GET /weddings` meets 최초 1회, and creating there
   closes their partner's ledger to them permanently. Check the pending token before
   the empty-list branch (`2026-08-22-decision-the-invite-link.md` §3).
9. **Neither endpoint carries a headcount**, and that is stated rather than
   defaulted — see §6.

## 6. Neither response carries the aggregate

The standing rule is that a mutation on a wedding-scoped resource answers
`{resource, headcount}`, because the ledger and the headcount are one screen and a
tap moves the number in place (`2026-08-20-decision-mutation-response-envelope.md`).
**Neither of these does, and the reason is the rule's own**: issuing a link and
accepting one change no 하객 and no 인원수. The couple is in 설정, not on the ledger,
and the number would be one nobody asked for on a screen that does not show it.

There is a structural half too, and it is the part worth writing down. Reaching the
headcount from `wedding/` is what forced `PATCH /weddings/{weddingId}` to be served
from `guest/` (`#178`). Carrying an aggregate here for symmetry's sake would move
the invite into `guest/` as well — a package where nothing about it belongs — to
publish a number that cannot have changed.

## 7. Open, and named rather than solved

- **Retention.** Nothing deletes spent, revoked or expired invites; they accumulate
  forever, exactly as `user_session` rows did before `#91` set 90 days. The same
  question applies and the same answer probably does, but it is a decision with a
  number in it and nobody has made it.
- **Rate limiting the accept path.** It is unauthenticated in spirit — a session
  costs one Google login — and each attempt is one indexed lookup on a 128-bit
  selector. The standing rule is per wedding and per link token, never IP-only, and
  a guesser has neither. `#98`'s answer (name the path in the edge's rules) is the
  shape this will take when it is taken; it is not in v1.
- **Whether a REVOKED token should say so, the way an expired one does.** Raised by
  `#186`'s review and **not decided here** — it is the founder's. The case: 신랑 mints
  a link, sends it on KakaoTalk, and taps 재발급 the next day not knowing the first
  was never opened. The partner then opens the link he actually sent and is told
  `INVITE_NOT_FOUND` with no advice, while a working link sits in the other person's
  hand. The safety argument for telling `INVITE_EXPIRED` apart applies verbatim — a
  revoked token is only recognisable to someone whose 256-bit verifier matched, so a
  guesser learns nothing — and the one-day life makes 재발급 normal rather than rare,
  which raises how often this lands. What holds it back is that a third code is a
  third piece of copy for a state the couple cannot see from either side. The shipped
  behaviour and the code shape are deliberately left alone until that is answered;
  `AcceptInviteContractTest`'s `a revoked token is gone rather than stale` is where
  the current answer is written down.
- **Whether Spring MVC's DEBUG body logging deserves a pin of its own.** `#186`'s
  security audit found the token's masking stopping at the DTO boundary — a
  `data class` `toString()` is what Spring prints on both legs at DEBUG, inside the
  100-character truncation window — and the fix taken here is to mask both DTOs,
  which is `2026-08-17-decision-log-masking-mechanism.md`'s own preference: close the
  pipe, do not filter it. **The pipe itself is still unpinned**: `LogLevelGuard`
  covers the Hibernate and pgjdbc loggers and nothing under `org.springframework.web`,
  so the next secret that travels in a request body inherits no protection. Adding
  the pin would refuse to boot any environment debugging ordinary MVC, which is why
  it was not taken unilaterally. It is an amendment to that record if anyone wants it.
- **In-app browsers.** `2026-08-22-decision-the-invite-link.md` §3's residual is
  unchanged: if KakaoTalk's webview hands the OAuth round trip to the system browser,
  the tab that returns is not the tab that stashed the token. The failure is safe and
  the recovery is to reopen the link, which is still valid for the rest of the day.

Refs `#181`, `#9`, `#69`, `#158`, `#166`, `#178`, `#182`,
`2026-08-22-decision-the-invite-link.md`,
`2026-08-22-decision-the-couples-two-seats.md`,
`2026-08-21-decision-one-wedding-per-person.md`,
`2026-08-20-decision-mutation-response-envelope.md`,
`2026-08-11-decision-baseline-schema-calls.md`,
`2026-07-30-decision-network-security.md`
