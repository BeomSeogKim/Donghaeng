import type { ReactNode } from 'react'

/*
 * The Tag part (design/components/parts/12-tag.html) — the word that labels a
 * row, and the same word with a state, which is the filter.
 *
 * IT IS NO LONGER A PILL, as of 2026-08-23: `--dh-radius-chip` is deleted and
 * radius is 0 (notes/2026-08-23-decision-the-form-language.md). The capsule was
 * 62% of a 44px row repeated 400 times and it alone read 원장 as an issue
 * tracker. Putting these words into the ledger's columns is `#216`; this change
 * is the corner.
 *
 * THE FILTER CHIP IS THIS COMPONENT, NOT A NEW ONE. The v1 inventory is a closed
 * set of ten, and "the filter chips are a Tag with a selected state" is what kept
 * it at ten when search landed (design/AGENTS.md). They live in one file because
 * they are one part; splitting them is how the eleventh component appears
 * without anyone deciding to add it.
 *
 * The pressed chip is filled with 자적. It is one of the very few places the
 * ledger fills colour at all, and that is the point — the couple has to see, at
 * a glance mid-scroll, that the list in front of them is not the whole list.
 */

const TAG =
  'inline-flex items-center px-2 py-0.5 text-meta leading-snug whitespace-nowrap'

const TAG_VARIANTS = {
  /** 7개 고정 분류 — the axis the ledger aggregates on. */
  group: 'bg-surface-sunken text-ink-muted',
  /** 측 — outlined rather than filled, so it never competes with the name. */
  side: 'border border-line-strong text-ink-muted',
  /** The couple's own label. Never aggregated on, so it is never a filled pill. */
  free: 'px-0 text-ink-faint',
} as const

export function Tag({
  children,
  variant,
}: {
  children: ReactNode
  variant: keyof typeof TAG_VARIANTS
}) {
  return <span className={`${TAG} ${TAG_VARIANTS[variant]}`}>{children}</span>
}

/*
 * The 2rem height is the part's, not an oversight of the 44px tap floor: the
 * filter row sits between the number and the list, and a 44px toolbar pushes the
 * list down on the device the ledger is mostly read on.
 */
const CHIP =
  'inline-flex min-h-8 items-center border px-3 text-meta font-semibold ' +
  'leading-none whitespace-nowrap transition-colors duration-(--dh-dur-fast) ease-standard'

const CHIP_STATES = {
  on: 'border-primary bg-primary text-ink-on-accent',
  off: 'border-transparent bg-surface-sunken text-ink-muted hover:border-line-strong',
} as const

/**
 * One filter chip.
 *
 * `aria-pressed` is the whole reason this is a component rather than a class
 * name: a chip whose fill says "on" while nothing announces it is a filter that
 * does not exist for anyone using a screen reader.
 */
export function FilterChip({
  children,
  onClick,
  pressed,
}: {
  children: ReactNode
  onClick: () => void
  pressed: boolean
}) {
  return (
    <button
      aria-pressed={pressed}
      className={`${CHIP} ${pressed ? CHIP_STATES.on : CHIP_STATES.off}`}
      onClick={onClick}
      type="button"
    >
      {children}
    </button>
  )
}
