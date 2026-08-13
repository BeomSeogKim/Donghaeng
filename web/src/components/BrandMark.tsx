/*
 * The brand mark — one of the three places the display face is allowed to
 * appear, and the page's <h1> on every screen that has no title of its own.
 *
 * Gold appears here as a hairline and nowhere as text. It is 3.3:1 on
 * porcelain, which clears the large-text threshold only above 24px; the mark is
 * set at 22px, so the word stays ink and the metal carries the accent
 * (design/AGENTS.md — contrast is a measured fact).
 */
export function BrandMark() {
  return (
    <div className="flex flex-col items-center gap-3">
      <h1 className="font-display text-title tracking-display">동행</h1>
      <span aria-hidden="true" className="h-px w-8 bg-[var(--dh-gold)]" />
    </div>
  )
}
