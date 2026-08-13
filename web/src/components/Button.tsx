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
 * The 44px floor is the tap minimum from the token file, not a number: buttons
 * in this product are pressed mid-scroll on a phone.
 */

const BASE =
  'inline-flex min-h-[var(--dh-tap-min)] items-center justify-center ' +
  'rounded-control px-4 text-body font-semibold leading-tight ' +
  'transition-colors duration-(--dh-dur-fast) ease-standard ' +
  'disabled:cursor-not-allowed disabled:opacity-45'

const VARIANTS = {
  primary: 'bg-primary text-ink-on-accent hover:bg-primary-hover',
  secondary: 'bg-surface text-ink border border-line-strong hover:border-ink-faint',
} as const

export type ButtonVariant = keyof typeof VARIANTS

export function buttonClassName(variant: ButtonVariant): string {
  return `${BASE} ${VARIANTS[variant]}`
}
