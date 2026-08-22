# Decision — 로그아웃은 원장 헤더를 떠나고, 마이페이지가 그 자리다 (2026-08-22)

Closes `#195` by **absorbing it into `#159`** (마이페이지), which is built in
the same pass rather than deferred. Founder's call.

**The call: 원장's pinned header goes back to one row — 하객 추가 and 설정.
로그아웃 moves behind 설정, onto 마이페이지.**

## What the two-row header cost

`#174` split the header's right side in two because three controls beside the
couple's name ellipsised on a phone. The fix was correct about the crowding and
wrong about which control to keep: the second row sits inside `sticky top-0`,
so it spends vertical space **on every scroll of the ledger**, above the number
and the filters, for an action taken about once a session.

`2026-08-07-design-screens-and-flow.md:120` had already answered this question
about the same three controls:

> None is frequent enough to spend screen furniture on; the bottom of the
> screen belongs to the list.

Removing the one-item ⋯ menu was right — 임포트 and 이메일 붙여넣기 went to
`post-v1` and took the menu's other entries with them. Replacing it with
permanent furniture was the part that needed undoing.

## Where 로그아웃 goes

`SettingsPage`'s own header refuses it — *"it sits on the screens a signed-in
person can be **parked**"* — and settings is passed through, not inhabited.
That argument stands, so 로그아웃 does not simply move one screen over.

**마이페이지 is its own screen** (`#159`: 원장에서 닿는 항목 하나, 그리고 그 뒤에
계정 정보와 로그아웃), and it is **reached from 설정, not from the ledger
header.** Putting its entry point in the header would restore the three
controls the two-row split was invented to fit.

Two taps from 원장 is the right depth for a once-a-session action, and
마이페이지 answers the question `#159` exists for — **누구로 로그인해 있는지** —
which is exactly the screen a person is parked on while deciding to leave. It
renders `GET /auth/me`; `name` is nullable there, so it renders a fallback
rather than assuming a string, and it offers no edit — the next login would
overwrite whatever was typed.

## The two logouts that stay exactly where they are

- **수락 화면.** The only exit on a screen with no other, and the "wrong Google
  account" recovery is built on it (`2026-08-22-decision-the-409-recovery-loop.md`).
- **웨딩 만들기.** Home to somebody waiting on an invite or left out of a
  partner's wedding; `#159` names it and it is not touched.

Neither is a duplicate of 마이페이지's. They are exits from dead ends; this one
is an account screen that happens to have the door on it.
