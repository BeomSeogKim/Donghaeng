import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { buttonClassName } from './Button'

/*
 * The frame for the screens a couple navigates TO and leaves again: 설정 and
 * 마이페이지.
 *
 * NOT `Screen`, WHICH MEANS SOMETHING ELSE — see `components/Screen`, which
 * says what it is and keeps its own list of who wears it — and not 원장's
 * full-bleed frame either. 원장 is home and everything frequent opens over it, so
 * these are the rare trips a couple makes and comes back from
 * (notes/2026-08-07-design-screens-and-flow.md).
 *
 * IT IS SHARED BECAUSE THERE ARE TWO OF THEM NOW. 마이페이지 (`#159`) arrived
 * wearing 설정's header exactly — same sticky bar, same back link, same title —
 * and two copies of a header are two headers that drift at the next change to
 * either. Nothing in here knows what is inside it.
 *
 * THE WAY BACK IS ON THE SCREEN, not left to the browser's Back button: on a
 * phone installed to the home screen there is no Back button at all.
 */
export function SubScreen({
  back,
  children,
  title,
}: {
  back: { label: string; to: string }
  children: ReactNode
  title: string
}) {
  return (
    <main className="min-h-[100dvh] bg-ground text-ink">
      <header className="sticky top-0 z-10 flex items-center gap-3 border-b border-line bg-ground px-4 py-3 md:px-6">
        <Link className={buttonClassName('secondary')} to={back.to}>
          <span aria-hidden="true" className="mr-1">
            ←
          </span>
          {back.label}
        </Link>
        {/* One of RIDIBatang's three places: the headcount, screen titles, the
            brand mark. */}
        <h1 className="font-display text-title leading-tight tracking-display">
          {title}
        </h1>
      </header>

      <div className="flex flex-col gap-6 py-6">{children}</div>
    </main>
  )
}

/**
 * One section of one of those screens, framed.
 *
 * Flush and hairline-separated on a phone, a bordered block on a laptop — the
 * ledger's rule, for the same reason: a card per section costs vertical rhythm
 * and buys nothing on a screen that is a list of two or three things.
 */
export function Section({ children, title }: { children: ReactNode; title: string }) {
  return (
    <section
      aria-label={title}
      className="border-y border-line bg-surface px-4 py-5 md:mx-6 md:max-w-104 md:border md:px-6"
    >
      <h2 className="text-lead font-semibold leading-snug">{title}</h2>
      <div className="mt-4">{children}</div>
    </section>
  )
}
