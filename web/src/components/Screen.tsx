import type { ReactNode } from 'react'

/*
 * The frame for the screens that are one short centered column: login, 웨딩
 * 만들기, and the two states the app can be in before it knows who you are.
 *
 * The ledger is not one of these — it is full-bleed, flush rows, edge to edge —
 * so this is not "the app shell". It exists so the few screens that genuinely
 * share a shape cannot drift apart, and it stops there.
 */
export function Screen({ children }: { children: ReactNode }) {
  return (
    <main className="flex min-h-[100dvh] flex-col items-center justify-center bg-ground px-6 text-ink">
      <div className="flex w-full max-w-96 flex-col items-center gap-8">{children}</div>
    </main>
  )
}
