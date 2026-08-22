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
