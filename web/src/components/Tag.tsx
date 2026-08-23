import type { ReactNode } from 'react'

/*
 * The Tag part (design/components/parts/12-tag.html) — the word that labels a
 * row, and the same word with a state, which is the filter.
 *
 * THERE IS NO PILL (2026-08-23, notes/2026-08-23-decision-the-form-language.md).
 * These were filled capsules; the capsule was 62% of a 44px row repeated 400
 * times, and it alone read 원장 as an issue tracker. What separates a label from
 * a name now is what separates every apparatus from every name on this screen —
 * 13px/400 muted against 17px/500 ink — and what separates one column group
 * from the next is the vertical 괘선, not a border per word.
 *
 * THE FILTER IS THIS COMPONENT, NOT A NEW ONE. The v1 inventory is a closed set
 * of ten, and "the filters are a Tag with a selected state" is what kept it at
 * ten when search landed (design/AGENTS.md). They live in one file because they
 * are one part.
 */

const TAG = 'truncate text-meta leading-snug'

const TAG_VARIANTS = {
  /** 7개 고정 분류 — the axis the ledger aggregates on. */
  group: 'text-ink-muted',
  /** 측 — the same voice; it is apparatus, not a badge. */
  side: 'text-ink-muted',
  /** The couple's own label. Never aggregated on, so it sits a step back. */
  free: 'text-ink-faint',
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
 * THE SELECTED FILTER IS A 2px 자적 UNDERLINE, NOT A FILL — `--dh-mark-w`, the
 * same marking thickness a focused field wears. Four filled capsules above the
 * list made the tools row the screen's second subject; the underline says the
 * same thing and spends no area saying it.
 *
 * The row still sits below the 44px tap floor on purpose: it is between the
 * number and the list, and a 44px toolbar pushes the list down on the device
 * 명부 is mostly read on. The padding under the label is what the underline
 * needs to clear the text.
 */
const CHIP =
  'inline-flex min-h-8 items-end pb-1 text-meta whitespace-nowrap ' +
  'border-b-(length:--dh-mark-w) border-transparent ' +
  'transition-colors duration-(--dh-dur-fast) ease-standard'

const CHIP_STATES = {
  on: 'border-(color:--dh-primary) font-semibold text-primary',
  off: 'text-ink-muted hover:text-ink',
} as const

/**
 * One filter.
 *
 * `aria-pressed` is the whole reason this is a component rather than a class
 * name: an underline that says "on" while nothing announces it is a filter that
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
