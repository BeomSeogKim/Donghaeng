# Decision — the couple are two accounts, and v1 is recut around it (2026-08-21)

A premise correction from the founder that moved scope. Recorded together because
each of the calls below follows from the one above it.

## 0. The correction

I had been reasoning from **"the couple share one device"** — it is in the wording
of `useLogout`'s KDoc and it shaped how I read every screen. The founder corrected
it: **each partner signs in with their own account, and either may edit the
ledger.**

Nothing in the backend model had to change, which is worth saying plainly because
it is the reason this correction cost hours rather than days. `membership` is
already a `(wedding_id, user_id)` table, every request already resolves
`user → membership → wedding`, and `guest_change` already records *who*. What was
missing was not the model but **the way in**.

## 1. A person belongs to exactly one wedding

Created or joined, never both, never two.

The founder's words were "웨딩 하나만 생성 가능", and the follow-up settled the
other half: **소속도 하나.** So a person who created a wedding cannot accept an
invite, and a person who joined by invite cannot create.

**This reverses a written spec decision.** `POST /weddings` allows a second wedding
*deliberately* today, and `docs/api-spec.md` says "Do not treat a 201 here as proof
they had none." Three files also state that one person may belong to several
weddings. `#158` carries the API refusal and the corrections; root `AGENTS.md` is
amended here, because every agent reads it before touching anything.

**Why it was not left permissive.** `web/` reads the caller's *first* wedding as
"the ledger" — sound only when there is at most one. Allowing several means a
ledger switcher in v1. And the reversal cost runs one way: allowing more later adds
a switcher, while narrowing later means deciding what to do with people who already
hold two.

## 2. The partner arrives by invite link

Not by email. The founder's call, and the reasons are the product's own: the couple
already coordinate on KakaoTalk, and an email invite requires knowing an address
that must match the Google account exactly — v1 is Google-only, so the margin is
thin.

**`#9` moves to v1**, because without it the correction in §0 is unachievable — only
the creator gets a membership, and the partner sees an empty list forever.

**`#69` moves with it.** A token in a URL path is logged in plaintext and echoed in
the error `instance`; that issue was opened about RSVP links, which are deferred,
but the invite link lands first and is the same class. A link is bearer
authority — whoever holds it enters the ledger and reads every guest's contact — so
expiry and single-use are conditions, not options.

**One ordering constraint falls out and is easy to miss**: the *accept* path must
sit in front of 웨딩 만들기. A partner who has not accepted yet has an empty
wedding list, and today's frontend sends an empty list straight to the create form.
Creating there gives them their own wedding — and by §1, closes their partner's
ledger to them permanently.

## 3. 유아식 waits — `#10` and `#14` go to `post-v1`

The trade for `#9`. **What is given up is precision, not correctness**, and the two
halves of that are worth separating:

- A couple with no 유아식 sees **an identical number**. The aggregation already
  falls back to the guest's expected party size when a guest has no meal rows, so
  an empty `guest_meal_count` is the default rather than a zero.
- A couple with 유아식 sees the total *including* infants, and cannot break it out.
  That is exactly today's behaviour.

**The cut is nearly free because the schema already holds the shape.** 유아식 is
not a column or an enum — it is a **row in `meal_type`, owned by the wedding**, named
in the couple's own words from their venue contract, with per-guest amounts in
`guest_meal_count`. Both tables ship in `V1`. Cutting `#10`/`#14` does not remove
anything built; it leaves those two tables empty. Restoring it later is two screens
and no migration.

### The argument I overstated, corrected here so it is not reused

I argued the cut by saying 유아식 needs somewhere to be entered (`#14`) *and*
somewhere to be read (`#18`), and that `#18` was already `post-v1`, so keeping
`#10`/`#14` would ship a half. **The second half of that is wrong.** Reading 유아
인원 does not require `#18`'s PC rail — a per-type breakdown member on the headcount
response shows it on mobile too, and `2026-08-11-decision-deletion-and-infant-meals.md`
§B already said the breakdown "is not only a PC-rail affordance". I had bound that
sentence to `#18`, which it does not say.

So the real price of keeping 유아식 in v1 was **two screens plus one aggregate
member**, with `#12` (하객 상세) necessarily first because `#14` hangs off it — not
"`#18` as well". The founder deferred it at that corrected price, which is the
number this record preserves.

## 4. A my-page entry — `#159`

Nothing in the app says who is signed in; the only render site of the session name
went with `HomePage` when 원장 became home. With two accounts on one ledger that
matters, because `guest_change` attributes every edit by name and an edit made from
a partner's session is silently attributed to them. Scope is deliberately small:
account info and 로그아웃 behind one entry, no editing.

**"Who last changed this guest" is a different question and stays `post-v1`.** The
record already exists; showing it is one screen, and whether two people actually
collide on one row is something real couples will answer better than we can.

Refs `#9`, `#69`, `#10`, `#14`, `#12`, `#18`, `#158`, `#159`,
`2026-08-11-decision-deletion-and-infant-meals.md`,
`2026-08-06-design-ledger-and-import.md`,
`2026-08-19-decision-launch-date-and-google-only.md`
