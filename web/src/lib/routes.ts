/*
 * The two paths that are named in more than one module, and nowhere else.
 *
 * This is not a route table — App.tsx is. It exists for the one line below it:
 * where a couple goes the moment their wedding exists.
 */

/** 웨딩 만들기 — reached from the home screen, and once the couple has one, never again. */
export const createWeddingPath = '/weddings/new'

/**
 * Where a newly created wedding lands.
 *
 * THIS IS THE LINE `#15` CHANGES. The flow is 로그인 → 웨딩 만들기 → 원장
 * (notes/2026-08-07-design-screens-and-flow.md), and the ledger does not exist
 * yet, so today it is the home screen as it stands. When the ledger takes a
 * route of its own, this constant moves and nothing else does — deliberately,
 * because a redirect written at the call site is a redirect that gets copied
 * to the next call site and then forgotten at one of them.
 */
export const ledgerPath = '/'
