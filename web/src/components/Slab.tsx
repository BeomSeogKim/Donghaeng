import type { ReactNode } from 'react'
import { BrandMark } from './BrandMark'

/*
 * 기물 — the frame every signed-in screen wears (design/AGENTS.md § Form,
 * notes/2026-08-23-decision-the-form-language.md).
 *
 * THREE SURFACES AND THEY STACK: 보자기 ground → 백자 slab → 자적 굽. The slab
 * is the only face work happens on, and it is one object rather than a page of
 * panels — which is what its edge says. THE 2px GOLD 구연 IS THE ONLY 구연 ON
 * THE SCREEN, and it is the whole of gold's second job. Anything else here that
 * wants an edge gets a beige hairline.
 *
 * THE SCREEN HAS NO TITLE, and that is a deletion rather than an omission. The
 * display face appears once per screen — on the ledger that is the headcount —
 * so 결혼식 이름 rides above the slab as a 13px running head. A second serif
 * makes the number look smaller and tips the screen toward stationery.
 */
export function Slab({
  children,
  head,
}: {
  children: ReactNode
  /** The running head — `RunningHead`, or nothing on a screen without a wedding. */
  head: ReactNode
}) {
  return (
    <main className="flex min-h-[100dvh] flex-col bg-ground text-ink">
      {head}
      {/*
       * `min-h-0` is what lets the list inside scroll instead of growing the
       * page: without it the slab is as tall as its content and the 굽 walks off
       * the bottom of the screen. `min-w-0` is the same rule on the other axis
       * and is not defensive — a flex item's `min-width` is `auto`, i.e. its
       * MIN-CONTENT, so on a phone the ledger's fixed columns would push the
       * slab wider than the viewport and take the 참석 column off the screen.
       */}
      <div className="mx-3 mb-3 flex min-h-0 min-w-0 flex-1 flex-col border-(length:--dh-rim-w) border-(color:--dh-gold) bg-surface md:mx-10 md:mb-10 md:flex-row">
        {children}
      </div>
    </main>
  )
}

/**
 * The line above the slab: the 인장, then what this ledger is.
 *
 * 결혼식 이름 IS WHAT `title` HOLDS (`#212`), and this component did not change
 * when it landed — `WeddingHead` decides what goes in the slot, including what
 * stands there for a wedding nobody has named.
 *
 * IT IS ONE LINE AND IT TRUNCATES. A running head that wraps pushes the slab
 * down on the device 원장 is mostly read on, and everything after 결혼식 이름 is
 * apparatus the couple already knows.
 */
export function RunningHead({
  action,
  title,
}: {
  /** 설정 or 명부 — one link, and there is never a second control here. */
  action: ReactNode
  title: string
}) {
  return (
    <header className="flex h-11 shrink-0 items-center justify-between gap-3 px-3 md:h-12 md:px-10">
      <div className="flex min-w-0 items-baseline gap-3 md:gap-4">
        <span className="shrink-0">
          <BrandMark />
        </span>
        <p className="truncate text-meta text-ink-muted tabular-nums">{title}</p>
      </div>
      {action}
    </header>
  )
}
