# Decision — 409를 두 번째로 만나면 화면이 그렇다고 말한다 (2026-08-22)

Closes `#198`, and binds `#164`, which owes the same sentence on the create
side. Founder's call.

**The call: the loop is not broken, it is spoken. A person who arrives at the
accept screen already belonging to a wedding is told so, and given the exits,
instead of being handed the form that just refused them.**

## The loop, and why neither half of it may be reverted

```
수락 제출 → 409 ALREADY_IN_A_WEDDING
  → 토큰 유지        (AcceptInvitePage: settled-but-alive)
  → "내 원장 열기"
  → LedgerPage: 빈 목록 + pendingInvite() ≠ null
  → Navigate replace → invitePath
  → 방금 거절당한 그 폼
```

`LedgerPage.tsx:82` is the bounce and `AcceptInvitePage`'s settled branch is
the offer. **Both are correct and were argued for separately**, which is what
makes this a design problem rather than a bug:

- **The token survives the 409** because `docs/api-spec.md` says it is not
  spent — the real partner still needs it — and because the "wrong Google
  account" recovery depends on it surviving a logout. The accept screen never
  says who is signed in, so that 409 is the only signal a person gets that they
  came in as the wrong account.
- **An empty ledger with a pending token goes to the accept screen** because
  that is exactly the state of a spouse who has not joined yet, and letting
  them create a wedding there strands them out of their partner's ledger
  forever (`#158`, `2026-08-21-decision-one-wedding-per-person.md`).

Reverting either one buys a worse failure than the one it fixes.

## Why not the two alternatives

- **Withhold 내 원장 열기 until `GET /weddings` confirms it.** Trades a loop for
  a wall: a person refused with 409 and shown no exit at all. A loop at least
  keeps a screen with a 로그아웃 on it.
- **Force the navigation and drop the token.** Burns the real partner's only
  live link — one day's life means they may not have opened it yet — and kills
  the wrong-account recovery, which needs the token to outlive the logout.

## What the screen does instead

**The 409 verdict is remembered for the session, and the second arrival renders
it.** `ALREADY_IN_A_WEDDING` is settled *for this person* — pressing 수락 again
cannot make them belong to two weddings — so remembering it is honest rather
than a workaround, and it is the distinction `AcceptInvitePage` already draws
between a settled attempt and a live token.

On that second arrival the screen states 이미 다른 웨딩에 속해 있습니다, keeps
내 원장 열기, and puts **로그아웃 where it can be seen** — because for the
wrong-account case logout *is* the recovery, not a last resort.

**Three constraints on the mechanism, and they are the whole of it:**

1. **The mark dies with the logout.** The next person to sign in on that tab
   must get a usable form — that is the recovery this exists to protect. It is
   cleared where the session is cleared, not left to `sessionStorage`'s own
   lifetime.
2. **The token is not touched.** The mark records what happened to *this
   caller*, never that the invite is dead.
3. **`#164` says the same thing on the create side**, from the same code. Two
   screens telling one story two ways is how the next reader learns the wrong
   one.

## On how rare it is

The bounce needs `GET /weddings` to disagree with the 409 — a replica read, a
late cache. Uncommon. **That is an argument for words, not against them**: a
person who hits this cannot reproduce it, cannot guess at it, and has nothing
on screen that admits it happened.
