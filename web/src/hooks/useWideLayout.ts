import { useSyncExternalStore } from 'react'

/**
 * Whether the app is drawing its wide layout — Tailwind's `md`, 768px.
 *
 * IT EXISTS FOR ONE ATTRIBUTE, AND ONLY BECAUSE THAT ATTRIBUTE IS A CLAIM.
 * 하객 추가 is a column of the slab on a laptop and an overlay on a phone
 * (notes/2026-08-23-decision-the-form-language.md), so `aria-modal` is true of
 * one layout and false of the other. CSS can be two things at once; an
 * assertion in the accessibility tree cannot, and asserting the wrong one tells
 * a screen-reader user the ledger behind the panel is unreachable when it is
 * sitting right beside it. **Everything else about the split stays in CSS** —
 * this is not a licence to branch layouts in JavaScript.
 *
 * `useSyncExternalStore` rather than `useEffect` + `useState`: `matchMedia` is
 * exactly the external store it is for, and it reads the right answer on the
 * first render instead of flipping after one.
 *
 * The breakpoint is written here in the one place, because Tailwind's `md:`
 * lives in its own defaults and there is no token for it — a second `768` at a
 * call site is how the CSS and the attribute would start disagreeing.
 */
const WIDE = '(min-width: 768px)'

export function useWideLayout(): boolean {
  return useSyncExternalStore(subscribe, isWide, serverFallback)
}

function subscribe(onChange: () => void): () => void {
  const query = window.matchMedia(WIDE)
  query.addEventListener('change', onChange)
  return () => query.removeEventListener('change', onChange)
}

function isWide(): boolean {
  return window.matchMedia(WIDE).matches
}

/**
 * There is no server render — this app is static files — so this only answers
 * the hydration-less first paint in a test environment without `matchMedia`.
 * A phone is the primary device, so the narrow layout is the safe guess.
 */
function serverFallback(): boolean {
  return false
}
