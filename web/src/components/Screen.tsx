import type { ReactNode } from 'react'
import { BrandFace, BrandMark } from './BrandMark'

/*
 * The frame for the screens a person meets before they have a ledger: 로그인,
 * 웨딩 만들기, 초대 수락, and the two states the app can be in before it knows
 * who you are.
 *
 * 원장 IS NOT ONE OF THESE — it wears `components/Slab` — so this is not "the
 * app shell". It exists so the few screens that genuinely share a shape cannot
 * drift apart, and it stops there.
 *
 * THE 자적 面 IS THE SAME SURFACE THE LEDGER'S 굽 IS, and putting it here is what
 * makes the entry flow one garment with the ledger rather than three loose forms
 * (notes/2026-08-23-decision-the-form-language.md). It is where the palette
 * shows what it came from: 자적 with gold on it, once, before the couple ever
 * sees a number.
 *
 * THE GOLD SEAM IS NOT AN ORNAMENT AND NOT A THIRD 구연. It is the 2px line
 * where the two faces meet — gold gets area and a position, never a length of
 * hairline laid over a beige one.
 *
 * IT STACKS ON A PHONE. The 자적 面 becomes a band above the form rather than a
 * column beside it, because a two-column layout on a 390px screen is one column
 * with the other one cut off.
 */
export function Screen({
  aside,
  children,
}: {
  /** The 자적 面's content — `ScreenFace`. Omitted on the screens that are only a notice. */
  aside?: ReactNode
  children: ReactNode
}) {
  if (aside === undefined) {
    return (
      <main className="flex min-h-[100dvh] flex-col items-center justify-center bg-ground px-6 text-ink">
        <div className="flex w-full max-w-96 flex-col items-center gap-8">{children}</div>
      </main>
    )
  }

  return (
    <main className="flex min-h-[100dvh] flex-col bg-ground text-ink md:flex-row">
      {/* `relative`, because the seam is positioned against this face's own
          trailing edge — bottom on a phone, right on a laptop. */}
      <div className="relative flex shrink-0 flex-col justify-center gap-8 bg-primary px-6 py-10 text-ink-on-accent md:w-115 md:px-15 md:py-0">
        {aside}
        <span
          aria-hidden="true"
          className="absolute inset-x-0 bottom-0 h-(--dh-seal-h) bg-[var(--dh-gold)] md:inset-x-auto md:inset-y-0 md:right-0 md:h-auto md:w-(--dh-seal-h)"
        />
      </div>

      <div className="flex flex-1 items-center justify-center px-6 py-10 md:px-10">
        <div className="flex w-full max-w-105 flex-col gap-8">{children}</div>
      </div>
    </main>
  )
}

/**
 * What sits on the 자적 面: the seal, a display-face heading, a gold rule, and
 * one sentence.
 *
 * ONE HEADING AND ONE SENTENCE, ALWAYS. This face is what the screen is about,
 * not where its work happens, and a second paragraph here is a paragraph nobody
 * reads on the way to a form.
 */
export function ScreenFace({
  eyebrow,
  heading,
  children,
}: {
  /** 시작하기 · 초대 — what this step is called, at 12px beside the seal. */
  eyebrow: string
  /**
   * One line, and it wraps if it must. A forced break here — `<br>` or a `\n` —
   * puts the break into the ACCESSIBLE NAME, so the screen a person hears stops
   * matching the screen everyone else reads. `text-balance` handles the wrap.
   */
  heading: string
  children: ReactNode
}) {
  return (
    <>
      <div className="flex items-center gap-3 md:absolute md:top-13 md:left-15">
        <BrandMark tone="accent" />
        <span className="text-label tracking-label text-primary-soft">{eyebrow}</span>
      </div>
      <div className="flex flex-col gap-6">
        {/* One of RIDIBatang's three places. There is no headcount on these
            screens, so this is the display face's single appearance. */}
        <h1 className="font-display text-face leading-snug font-bold tracking-display text-balance">
          {heading}
        </h1>
        <span aria-hidden="true" className="h-(--dh-seal-h) w-17 bg-[var(--dh-gold)]" />
        <p className="text-body leading-body text-primary-soft">{children}</p>
      </div>
    </>
  )
}

/** 로그인's face — the one screen where the wordmark itself is the heading. */
export function LoginFace({ children }: { children: string }) {
  return (
    <div className="flex flex-col gap-8">
      <BrandFace />
      <p className="font-display text-face leading-snug tracking-display text-balance">
        {children}
      </p>
      <p className="max-w-95 text-body leading-body text-primary-soft">
        하객 명부와 식대 인원이 한 화면에 있습니다. 두 사람이 같은 숫자를 봅니다.
      </p>
    </div>
  )
}
