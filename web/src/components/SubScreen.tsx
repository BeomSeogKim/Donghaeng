import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { Slab } from './Slab'
import { WeddingHead } from './WeddingHead'

/*
 * The frame for the screens a couple navigates TO and leaves again: 설정 and
 * 마이페이지.
 *
 * IT IS THE SAME 기물 AS THE LEDGER — 보자기 ground, 백자 slab, one gold 구연 —
 * because a trip a couple takes from their ledger should not land them on a
 * different object (notes/2026-08-23-decision-the-form-language.md). What it
 * does not have is a 굽: there is no number on these screens, so there is
 * nothing for one to carry.
 *
 * THESE TWO STILL HAVE A TITLE, and 하객 명부 does not. That is the three-voices
 * rule doing its work rather than an inconsistency: the ledger spends its one
 * display appearance on the headcount, and a screen with no headcount has it
 * spare. It is also what these screens most need to say — a couple arrives here
 * on purpose and has to know where "here" is.
 *
 * NOT `Screen`, WHICH MEANS SOMETHING ELSE — see `components/Screen`, which says
 * what it is and keeps its own list of who wears it.
 *
 * IT IS SHARED BECAUSE THERE ARE TWO OF THEM NOW. 마이페이지 (`#159`) arrived
 * wearing 설정's header exactly, and two copies of a header are two headers that
 * drift at the next change to either. Nothing in here knows what is inside it.
 *
 * THE WAY BACK IS ON THE SCREEN, not left to the browser's Back button: on a
 * phone installed to the home screen there is no Back button at all. It is the
 * running head's one link, where the ledger keeps 설정.
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
    <Slab
      head={
        <WeddingHead
          action={
            <Link
              className="shrink-0 text-meta text-primary hover:text-primary-hover"
              to={back.to}
            >
              {back.label}
            </Link>
          }
        />
      }
    >
      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-6 md:px-11 md:py-11">
        <div className="mx-auto flex w-full max-w-175 flex-col gap-9">
          {/* One of RIDIBatang's three places: the headcount, screen titles, the
              brand mark. */}
          <h1 className="font-display text-title leading-tight tracking-display">
            {title}
          </h1>
          {children}
        </div>
      </div>
    </Slab>
  )
}

/**
 * One section of one of those screens.
 *
 * A LETTERSPACED 12px HEADING OVER A 1px RULE, not a bordered block. The slab is
 * already an object with an edge; a bordered card inside it is a box in a box,
 * which is exactly what the form rules deleted everywhere else.
 */
export function Section({ children, title }: { children: ReactNode; title: string }) {
  return (
    <section aria-label={title}>
      <h2 className="border-b border-line-strong pb-3 text-label tracking-label text-ink-faint">
        {title}
      </h2>
      <div className="mt-5">{children}</div>
    </section>
  )
}
