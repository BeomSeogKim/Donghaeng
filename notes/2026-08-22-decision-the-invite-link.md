# Decision — the invite link lives one day, and its token never reaches the server in a URL (2026-08-22)

> **Amended 2026-08-22 — §3's residual is stated wrongly.** That section says an
> in-app browser losing `sessionStorage` fails safely, with the person landing
> "signed in with nothing pending". **They never get signed in at all**: Google
> refuses OAuth in embedded user agents (`disallowed_useragent`) and KakaoTalk's
> browser is one, so the round trip stops at Google's error page where nothing of
> ours can speak. The warning therefore has to be *pre-tap*. **Everything §1 and
> §2 decide is unaffected.** Three risks this record never considered — logout
> leaving a live credential in the tab, the clipboard, and a hostile *inbound*
> fragment — are in `2026-08-22-decision-the-invite-links-residuals.md`.

Closes `#69`, which was an `open-question` and therefore could only be closed by
a record. `#9` (파트너 초대) is unblocked by it: `#69` decided the token's
shape, and nothing about `#9`'s endpoints could be drawn until it did.

## 1. The founder's call — one day, and it can be reissued

**최대 1일.** A link stops working a day after it is made, and the couple can
**make a new one** when it does.

The question put to the founder was how couples actually use it: is this a link
you send on KakaoTalk and your partner opens three days later, or is it closer to
"we are sitting together, tap it now"? The answer is the second, with reissue as
the escape hatch for the first.

**This is a security improvement and not only a UX call, which is worth saying
because it was not the reason it was asked.** `#9` already established that the
link is bearer authority — whoever holds it enters the ledger and reads every
guest's contact. A KakaoTalk room is forwardable, and a forwarded room message is
searchable in that room forever. A one-day life means a link that leaks by being
forwarded is inert by the next day, and the couple is never asked to think about
it. A week-long link would have put a live credential in a chat room for a week.

**Reissue is what makes one day affordable.** Without it, a couple whose partner
was busy yesterday is stuck; with it, the cost of a short life is one tap in 설정.

### What follows and is not separately decided

- **Reissuing kills the previous token.** Otherwise a couple who taps 재발급 three
  times has three live credentials in three places, and the short life we just
  bought is undone by holding several at once. At most one live invite per seat.
- **Single-use stands** (`#9`): accepting consumes the token. Expiry and single
  use answer different failures — an unopened link going stale, and an opened link
  being replayed.
- A wedding whose second seat is already claimed has nothing to invite; 재발급
  cannot exist there. That is a screen state, not a rule to enforce twice.

## 2. The token is carried in the URL **fragment**, never in the path

This is the engineering half, and it is what `#69` actually asked.

```
https://<app>/invite#t=<token>
```

The fragment is **never sent to the server**. Not in the request line, so not in
an access log; not in an error response's `instance`; not in a `Referer` to any
third party. The accept screen reads it with JavaScript, and the only time the
token travels to us is in the **body of the accept POST**, which is not logged.

That is the whole of `#69`'s complaint, which was that a token in the path is
recorded in plaintext in places nobody thinks of as storage.

## 3. The same choice closes the OAuth round trip, which was the harder problem

Found on 2026-08-22 while auditing `#9` and confirmed in code: **the person
clicking an invite is almost always signed out.** A partner tapping a link in a
KakaoTalk room has no reason to hold our session. So the real path is

```
click (signed out) → Google → our callback → ??? → back to that invite
```

and the last arrow did not exist. `web/src/App.tsx` sends an unauthenticated
visitor to `/login` and **discards where they came from**;
`OAuthLoginSuccessHandler` redirects only to the configured frontend origin.
Landing at the root with no wedding, the partner meets 웨딩 만들기 — and creating
there closes their partner's ledger to them permanently, by the one-wedding rule
(`2026-08-21-decision-one-wedding-per-person.md`). The invite flow walks straight
into the trap `#9` had already written down.

**The fragment answers this too, because `sessionStorage` survives the round
trip.** The accept screen stashes the token before leaving for Google; the tab
comes back to the same origin holding it; the root reads it and routes to the
accept action.

**So no `returnTo` is built, and no server redirect target becomes a parameter.**
This reverses what was posted on `#9` earlier today: `web/src/pages/LoginPage.tsx`
says "there is nothing here to pass along and no returnTo to build", and that
comment **stays true**. Nothing in `OAuthLoginSuccessHandler` changes. A
`returnTo` would have been a second place a token-bearing URL could be logged,
and refusing to build one is the same decision as §2, not a separate convenience.

**The ordering constraint `#9` named becomes implementable rather than
structural**: "the accept path must sit in front of 웨딩 만들기" is a check of
`sessionStorage` before the empty-list guard, not a rearrangement of routing.

### The residual, named rather than solved

Korea's in-app browsers — KakaoTalk's above all — are where this can still fail.
If a link opens in an in-app webview and the OAuth round trip leaves it for the
system browser, the tab that comes back is not the tab that stashed the token, and
`sessionStorage` is gone. **The failure is safe** (the token is not lost to
anyone, the person simply lands with nothing pending and sees the ordinary signed-in
screen) but it is a dead end for that couple. `#9` owns finding out whether it
happens, and owns making the dead end say something useful — reopening the link
after signing in is the natural recovery, and the link is still valid for a day.

## 4. What this does not decide

- **The accept endpoint's shape** — `#9`'s work. What binds it: the token arrives
  in a body, the seat already exists so acceptance is an `UPDATE` of one identified
  row (`2026-08-22-decision-the-couples-two-seats.md` §2), and a caller who already
  belongs to a wedding is refused with the same `ALREADY_IN_A_WEDDING` the create
  path uses, from the same check.
- **The invite table's columns.** `V1__baseline_schema.sql` deliberately left this
  out — "mechanism not designed, so do not guess the columns". The mechanism is
  designed now; `#9` writes the table, and **applies it as `donghaeng_app`**.
- **RSVP links** (`#27`) stay `post-v1`. `#69` was opened about those and lands
  here because the invite arrives first and is the same class of thing. Whether
  a guest-facing link inherits one day is not decided here — a guest has no
  account to sign into, so its round trip is a different problem.

Refs `#69`, `#9`, `#158`, `#148`, `#27`,
`2026-08-21-decision-one-wedding-per-person.md`,
`2026-08-21-decision-two-accounts-and-the-v1-recut.md`,
`2026-08-22-decision-the-couples-two-seats.md`
