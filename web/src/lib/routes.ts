/*
 * The paths that are named in more than one module, and nowhere else.
 *
 * This is not a route table — App.tsx is. It exists for the one line below it:
 * where a couple goes the moment their wedding exists.
 */

/** 웨딩 만들기 — reached from the home screen, and once the couple has one, never again. */
export const createWeddingPath = '/weddings/new'

/**
 * 설정 — the wedding's own information, and the shell every other setting joins.
 *
 * A ROUTE RATHER THAN A SHEET: 원장 ↔ 설정 is a trip a couple makes rarely and
 * leaves, unlike 하객 추가 and 하객 상세, which open over the ledger and never
 * navigate (notes/2026-08-07-design-screens-and-flow.md).
 */
export const settingsPath = '/settings'

/**
 * 마이페이지 — the account, and the one screen a signed-in person is parked on
 * with something to read.
 *
 * REACHED FROM 설정, NOT FROM THE LEDGER HEADER, and that is the whole reason it
 * is a route of its own rather than a section of 설정. An entry point in the
 * header puts back the third control the two-row split was invented to fit, and
 * 설정 argues in as many words that it is passed through rather than inhabited —
 * so 로그아웃 did not shift one screen sideways, a screen it belongs on was made
 * (notes/2026-08-22-decision-logout-leaves-the-ledger.md).
 *
 * IT IS `/me` BECAUSE `GET /auth/me` IS WHAT IT DRAWS. It is not under
 * `/settings`: the wedding's information and the person's account are two
 * subjects, and only the navigation between them is shared.
 */
export const myPagePath = '/me'

/**
 * Where a newly created wedding lands — 원장.
 *
 * IT STAYED `/` WHEN THE LEDGER LANDED (`#15`), and that is the decision rather
 * than an omission: 원장 is home. There is essentially one screen, everything
 * else opens over it, and a ledger at its own path would need a home screen in
 * front of it to link there (notes/2026-08-07-design-screens-and-flow.md).
 *
 * The constant survives anyway, because a redirect written at the call site is
 * a redirect that gets copied to the next call site and then forgotten at one
 * of them.
 */
export const ledgerPath = '/'

/**
 * 초대 수락 — where a KakaoTalk link lands, and the only route a signed-out
 * person may open besides 로그인.
 *
 * THE TOKEN IS NOT PART OF THE PATH AND NEVER WILL BE. It rides in the
 * fragment, `#t=…`, which is the only part of a URL that is never sent to a
 * server — so it stays out of access logs, out of `Referer`, and out of every
 * error document's `instance` (notes/2026-08-22-decision-the-invite-link.md §2).
 * `lib/invite.ts` is what assembles the link.
 */
export const invitePath = '/invite'
