/*
 * 인장 — the first of gold's three jobs, and one of the three places the display
 * face is allowed to appear.
 *
 * IT IS A WORD WITH A RULE UNDER IT, AND THE RULE IS EXACTLY AS WIDE AS THE
 * WORD. That is what makes it a seal rather than a divider: gold gets AREA and a
 * position instead of a length of hairline (design/AGENTS.md — gold has exactly
 * three jobs). A full-width gold rule was what this replaced; laid over a beige
 * hairline it became that hairline's ornament, and by area it outweighed every
 * gold that was carrying data.
 *
 * GOLD IS NEVER THE TEXT. It is 3.3:1 on porcelain, so the word stays ink — or
 * 백자 when the mark sits on the 자적 panel — and the metal is the rule alone.
 */
export function BrandMark({
  heading = false,
  tone = 'ink',
}: {
  /** Whether this is the page's `<h1>`. Exactly one mark per screen may be. */
  heading?: boolean
  /** `accent` for the mark that sits on 자적. */
  tone?: 'ink' | 'accent'
}) {
  const Word = heading ? 'h1' : 'span'

  return (
    // `inline-flex` with `items-start`, so the rule takes the word's width and
    // not the container's — the whole point of the 인장.
    <span className="inline-flex flex-col items-start gap-0.5">
      <Word
        className={`font-display text-lead leading-tight tracking-display ${
          tone === 'accent' ? 'text-ink-on-accent' : 'text-ink'
        }`}
      >
        동행
      </Word>
      <span aria-hidden="true" className="h-(--dh-seal-h) w-full bg-[var(--dh-gold)]" />
    </span>
  )
}

/**
 * The same seal, at the size the 자적 panel wears it — 로그인's face, and the
 * only place in the product the wordmark is large.
 *
 * A SEPARATE COMPONENT RATHER THAN A `size` PROP, because the rule is not the
 * word's width here: it is a fixed 68px stroke set below a 46px word, which is a
 * different drawing rather than the same one scaled.
 */
export function BrandFace() {
  return (
    <div className="flex flex-col gap-5">
      <h1 className="font-display text-display-compact leading-tight font-bold tracking-display text-ink-on-accent">
        동행
      </h1>
      <span aria-hidden="true" className="h-(--dh-seal-h) w-17 bg-[var(--dh-gold)]" />
    </div>
  )
}
