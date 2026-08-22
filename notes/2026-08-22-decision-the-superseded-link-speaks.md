# Decision — 밀려난 초대 링크는 자기 코드를 갖는다 (2026-08-22)

Closes `#193`. Founder's call. Amends
`2026-08-22-decision-the-partner-invite.md` §7 and the invite errors in
`docs/api-spec.md`.

**The call: a token killed by a 재발급 answers `INVITE_SUPERSEDED`, not
`INVITE_NOT_FOUND`.**

## The dead end it removes

발급 is 재발급 — issuing a second link kills the first, deliberately, so that a
couple tapping three times does not hold three live credentials
(`2026-08-22-decision-the-invite-link.md` §1). The person holding the killed
link then opens it and reads *"이 링크는 사용할 수 없습니다"*, which says
nothing about what to do — **while a working link sits on the other person's
phone.**

**A one-day life makes this ordinary, not an edge case.** The short lifetime
was the right call and this is its cost: 재발급 is a daily action, so a
superseded link in someone's KakaoTalk is a daily state.

## Why it is safe to say

The identical argument `INVITE_EXPIRED` already stands on, and it transfers
without modification. The code is reached **only after the verifier matched** —
`WeddingInviteService.accept` checks `presented.matches(...)` before it asks
whether the invite is live — so it is a sentence said to somebody who presented
a correct 256-bit secret. A guesser never gets far enough to learn anything, and
what they would learn is that a seat exists, which the selector already told
them.

## Where it lives, and what stays `INVITE_NOT_FOUND`

`WeddingInvite` already keeps the three deaths apart in separate columns —
`expiresAt`, `acceptedAt`, `revokedAt` — so the new answer is a distinction the
schema was already carrying and the service was collapsing.

- **`revokedAt` set, `acceptedAt` null → `INVITE_SUPERSEDED`** (404). Killed by
  a 재발급.
- **`acceptedAt` set → `INVITE_NOT_FOUND`.** Already spent. Telling a second
  person that a link was used tells them about somebody else.
- **`consume` returning 0 → `INVITE_NOT_FOUND`, unchanged.** That rowcount is a
  race between the read and the write, and it is the one path where the caller
  cannot be told which death it was without a second read that would race
  again. Rare enough to leave generic; wrong to guess at.

`AcceptInviteContractTest`'s *"a revoked token is gone rather than stale"* is
where the old answer was asserted, and it becomes the assertion of the new one.

## What the two people see

- **The receiver**: *새 링크가 발급되었습니다 — 파트너에게 최신 링크를
  요청하세요.* Which is the same recovery `INVITE_EXPIRED` offers, arrived at
  from the other direction.
- **The issuer**: 재발급 **warns before it acts** — 이전 링크는 무효가 됩니다 —
  rather than reporting afterwards. `201` still does not distinguish a first
  issue from a replacement and is not being changed to: the response is not
  where somebody learns what a button they have not pressed yet will do.
