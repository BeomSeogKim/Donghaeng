# Decision — what the invite link's threat model missed, and one thing it got factually wrong (2026-08-22)

Written after `#182` (the accept screen) and its security audit. `2026-08-22-decision-the-invite-link.md` decided the token's shape and it still stands; this record fixes one false sentence in it and names three risks it never considered. Nothing here reverses a founder decision.

## 0. The correction — the KakaoTalk failure is not what I wrote

That record's §3 says of an in-app browser losing `sessionStorage`:

> **The failure is safe** — the token is not lost to anyone, the person simply lands with nothing pending and sees the ordinary signed-in screen.

**They never get signed in at all.** Two facts, both established while building `#182`:

- **Google refuses OAuth in embedded user agents by policy** — `disallowed_useragent`, stated in Google's own native-app OAuth documentation.
- **KakaoTalk's in-app browser is that WebView**, on both platforms. The `kakaotalk://web/openExternal` escape appears in hundreds of public projects for exactly this reason, and the same body of work establishes that Chrome Custom Tabs and SFSafariViewController are **not** blocked and are UA-indistinguishable from the system browser.

So the tab does not come back holding the wrong thing. **It stops at Google's own error page, where nothing of ours can speak.**

That changes where the warning has to live: **before the tap, on the accept screen, beside the login button and never instead of it.** A UA match is wrong in both directions, and taking the login away from someone whose login works is the worse mistake — so the match is a whitelist, every entry of which must be defensible as a test row, and the notice never gates the button.

It also changes the recovery copy. "Open this page in another browser" would hand over a **tokenless** link, because the fragment is cleared by then. The copy points at the chat room instead: 대화방에서 링크를 길게 눌러 복사한 뒤 크롬이나 사파리에 붙여넣기.

**The breakout is refused, and not as a judgement call.** `docs/api-spec.md` already says *"Never put the token in a path or a query string, ours or anyone's"*, and `kakaotalk://web/openExternal?url=…` is a query string of anyone's. Android's `intent://` form is worse: it passes through the intent resolver, where **any installed app registering the scheme can receive it** — a bearer credential read by software neither we nor the user chose. If it is ever wanted, it is an amendment to this record and to that spec line, dated and argued, never a silent exception in a diff.

## 1. Logout leaves a live credential in that tab — accepted

`sessionStorage` deliberately survives 로그아웃, because "I signed in with the wrong Google account" recovers by signing out and back in, and that only works if the token is still there. That decision is right and it is why the accept screen carries a 로그아웃 door at all — its occupant holds no wedding, so 원장 sends them back and 웨딩 만들기 is the one screen they may never be handed.

**The residual, which neither invite record mentioned:** on a shared or borrowed device, the next person to sign in **in that same tab** meets an empty `GET /weddings`, is diverted to the accept screen, and can take the seat with their own account.

Accepted, and bounded by three things: `sessionStorage` is tab-scoped and dies with the tab, the link is sitting in the chat room anyway, and accepting still requires typing a name. Recorded so the next session does not re-derive it.

## 2. The clipboard is a sixth place the token lives

The 설정 screen writes the whole token-bearing URL to the system clipboard, because thumb-selecting sixty characters of base64 is the difference between one tap and giving up. **Nothing we own can clear it.** iOS Universal Clipboard syncs it to the person's other devices; on Android it is broadly readable.

This is inherent, not a defect — the link exists to be pasted into KakaoTalk. It is recorded because the lifecycle table in `#182` listed five places the token lives and this was not one of them.

## 3. A hostile inbound fragment binds a victim permanently

**The threat model ran outward only.** It reasoned about our token leaking to others and never about someone else's token being pushed at us.

An attacker issues an invite for **their own** wedding and sends `https://<app>/invite#t=<theirs>`. A victim who accepts is bound to the attacker's wedding — and `2026-08-21-decision-one-wedding-per-person.md` makes that binding total, while **no endpoint deletes a wedding or releases a seat.** There is no un-join.

**This is phishing, not a vulnerability.** Nothing auto-submits — the mutation is reachable only from the form's submit handler; the screen says 초대 수락; a name must be typed and a button pressed. What earns it a record is that it is **irreversible**, which is rare in this product.

And there is a real tension with a decision that is correct: **the accept screen cannot name the wedding it is joining**, because the API deliberately publishes nothing about an invite before it is accepted — publishing it would be exactly the oracle the two-404 design closes.

So the honest options are a **self-service seat release** or nothing plus this record. `web/` cannot fix it alone. **Left open for the founder**; not held against `#182`.

## What is unchanged

The fragment, `sessionStorage` across the OAuth round trip, no `returnTo`, one-day life, reissue killing the previous token, single use. All still right, all still the founder's calls.

Refs `#182`, `#9`, `#69`, `#158`,
`2026-08-22-decision-the-invite-link.md`,
`2026-08-22-decision-the-partner-invite.md`,
`2026-08-21-decision-one-wedding-per-person.md`
