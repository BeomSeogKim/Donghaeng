/*
 * The Button part (design/components/parts/10-button.html) as class names rather
 * than as a component, because its two call sites are not the same element:
 * starting login is a browser navigation and therefore an `<a href>`, while
 * signing out is a real `<button>`. A component covering both would need an `as`
 * prop before there is a third call site to justify one; a class name paints
 * either.
 *
 * Two variants exist because two are used. `ghost` and `destructive` are in the
 * part and are not written here — a destructive button also has rules of its own
 * (always an outline, always a verb) that belong with the screen that needs one.
 *
 * THE CORNER IS SQUARE, and `rounded-control` still says so out loud rather than
 * being dropped: the token is 0 on purpose as of 2026-08-23, and a class that
 * reads it is what makes that a decision rather than a default nobody set
 * (notes/2026-08-23-decision-the-form-language.md).
 *
 * The 44px floor is the tap minimum from the token file, not a number: buttons
 * in this product are pressed mid-scroll on a phone.
 */

const BASE =
  'inline-flex min-h-[var(--dh-tap-min)] items-center justify-center ' +
  'rounded-control px-6 text-body font-semibold leading-tight ' +
  'transition-colors duration-(--dh-dur-fast) ease-standard ' +
  'disabled:cursor-not-allowed disabled:opacity-45'

const VARIANTS = {
  primary: 'bg-primary text-ink-on-accent hover:bg-primary-hover',
  secondary: 'bg-surface text-ink border border-line-strong hover:border-ink-faint',
  /**
   * The button on the 자적 굽 — 하객 추가, and the only control that lives on
   * that face. Outlined in gold rather than filled, because a filled button on
   * 자적 has nothing to be filled WITH: 백자 would out-shout the headcount above
   * it. Gold is the edge here and never the label — 3.3:1 in light.
   */
  onFoot: 'border-(color:--dh-gold) border text-ink-on-accent hover:bg-primary-hover',
} as const

export type ButtonVariant = keyof typeof VARIANTS

export function buttonClassName(variant: ButtonVariant): string {
  return `${BASE} ${VARIANTS[variant]}`
}
