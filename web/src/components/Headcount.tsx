import type { ReactNode } from 'react'
import { useHeadcount } from '../hooks/useHeadcount'
import { buttonClassName } from './Button'

/*
 * 식대 인원 — the Stat part (design/components/parts/15-stat.html), and as of
 * 2026-08-23 it is **the 굽**: the 자적 face the 백자 slab stands on, rather
 * than a KPI card beside the list (notes/2026-08-23-decision-the-form-language.md).
 * A right-hand rail on a laptop, the slab's head on a phone — one element,
 * reordered.
 *
 * IT IS THE SAME SCREEN AS THE LEDGER, and that is the product's first fixed
 * point: tapping attendance moves this number in place, so the couple has to
 * see *which* figure moved without leaving the list.
 *
 * TWO NUMBERS, AND THERE IS NO THIRD. 식대 인원 and 보증인원, because those are
 * the two the API publishes and it says in as many words that there is no third
 * (docs/api-spec.md § GET .../headcount). **The approved design draws a
 * 신랑측 · 신부측 · 참석 · 불참 breakdown under the meter and it is not built
 * here**: those are four numbers no endpoint sends, and deriving them from the
 * loaded rows would be this client computing an aggregate — the one thing it
 * may never do, because the ledger's rows are filtered and the total is not.
 * It is `#18`, and it arrives when the endpoint does.
 *
 * ONE OF RIDIBatang's THREE PLACES: the headcount, screen titles, the brand
 * mark. Never the list. **On this screen it is the ONLY one** — 원장's own
 * screen title was deleted so that the display face appears exactly once, and
 * 결혼식 이름 is a 13px running head instead. Every digit here is tabular,
 * because a number whose width shifts as it counts reads as unstable.
 */
export function Headcount({
  action,
  weddingId,
}: {
  /** 하객 추가 — the one control that lives on the 굽. */
  action: ReactNode
  weddingId: number
}) {
  const headcount = useHeadcount(weddingId)

  /*
   * A NUMBER THE SERVER DID NOT JUST CONFIRM IS NOT A NUMBER. React Query keeps
   * the last successful data through a failed refetch — and a refetch is
   * ordinary here, because `staleTime` is 0 and the window regains focus — so
   * without this the couple would be shown a 64px figure from some earlier
   * moment, at full confidence, with a 13px note beside it. The list does the
   * same thing for the same reason: its failure replaces the rows it was
   * holding rather than sitting under them.
   */
  const counted = headcount.isError ? undefined : headcount.data
  const meal = counted?.mealHeadcount
  const guaranteed = counted?.guaranteedHeadcount

  return (
    <section aria-busy={headcount.isPending} aria-label="인원수" className={FOOT}>
      {/*
       * ONE BUTTON, TWO POSITIONS. `md:contents` dissolves this wrapper on a
       * laptop so its children become the 굽's own column — the number stays at
       * the top and 하객 추가 drops to the foot on `order`. Rendering it twice
       * and hiding one would put two controls with one name in the accessibility
       * tree, for a layout difference.
       */}
      <div className="flex flex-wrap items-end justify-between gap-4 md:contents">
        <div>
          {/*
           * On 자적, and 자적 is dark, so the label is 자적 soft rather than
           * ink-muted: this is what says which number the couple is looking at,
           * so it is text rather than decoration.
           */}
          <p className="text-label tracking-label text-primary-soft">식대 인원</p>

          <p className="mt-3 flex items-baseline gap-2">
            {/*
             * AN UNCOUNTED NUMBER IS NEVER DRAWN AS 0. An empty ledger genuinely
             * is 0 and says so; a read that is still in flight or that failed
             * has no number at all, and the two may not look the same on the one
             * screen whose claim is that its numbers are never wrong. The dash
             * holds the position so nothing jumps when the number lands.
             */}
            <span className="font-display text-display-compact leading-tight font-bold tracking-figure tabular-nums md:text-display">
              {meal ?? '—'}
            </span>
            <span className="text-body text-primary-soft">명</span>
          </p>
        </div>
        <div className="md:order-1 md:mt-auto md:w-full md:pt-8">{action}</div>
      </div>

      {/*
       * 보증인원 IS ABSENT, NOT NULL, until the couple has agreed one with their
       * venue, and 미설정 is an ordinary state rather than an unfinished one —
       * couples sign up before they book. Nothing is drawn in its place: a
       * comparison against a number nobody gave us would be ours rather than
       * their venue's, and the empty slot would ask a question this screen is
       * not where to answer. 설정 · 웨딩 정보 is.
       */}
      {meal !== undefined && guaranteed != null && (
        <Comparison guaranteed={guaranteed} meal={meal} />
      )}

      {/*
       * The ledger's failure and this one are two reads and two answers: a
       * number that did not arrive does not blank the list, and a list that did
       * not arrive does not blank the number. A 401 never reaches here — the
       * client answers every one of those with the login screen
       * (lib/queryClient.ts).
       */}
      {headcount.isError && (
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <p className="text-meta leading-snug text-primary-soft">
            인원수를 불러오지 못했습니다.
          </p>
          <button
            className={buttonClassName('onFoot')}
            onClick={() => void headcount.refetch()}
            type="button"
          >
            다시 시도
          </button>
        </div>
      )}

      <p className="mt-4 hidden text-label leading-body text-primary-soft md:order-2 md:block">
        보증인원은 예식장이 정하는 숫자입니다. 동행은 입력된 값만 더합니다.
      </p>
    </section>
  )
}

/*
 * 244px on a laptop, and it is the last thing in the slab; a band across the
 * top of it on a phone. `order-last` rather than a different DOM order, so the
 * number is still read first — 숫자 → 도구 → 목록 is the couple's own order.
 */
const FOOT =
  'flex shrink-0 flex-col bg-primary px-4 py-4 text-ink-on-accent ' +
  'md:order-last md:w-61 md:px-6 md:py-7'

/**
 * 대비는 화면의 뺄셈이다 — the server sends two numbers and never a difference,
 * a percentage or a recommendation (docs/api-spec.md § GET .../headcount).
 *
 * 초과 is 치자, the attention colour, and never 대홍: being over the number the
 * venue wrote down is something to see, not a destroyed row. Red belongs to
 * destroying data only.
 */
function Comparison({ guaranteed, meal }: { guaranteed: number; meal: number }) {
  const over = meal > guaranteed
  const difference = Math.abs(meal - guaranteed)
  const filled =
    guaranteed > 0 ? Math.min(100, Math.round((meal / guaranteed) * 100)) : 100

  return (
    <>
      {/*
       * 기준선 — the third of gold's three jobs, and the one that carries data.
       * A 10px FACE rather than a hairline: gold works by area here, which is
       * what stops it becoming the decoration of the line underneath it. It
       * says the same thing the two figures below say, at a glance and
       * mid-scroll, which is why it carries no text of its own and is hidden
       * from the screen reader rather than announced twice. It settles over
       * 260ms, deliberately slower than the 120ms control, so the couple can
       * see which number moved.
       */}
      <div
        aria-hidden="true"
        className="mt-6 h-(--dh-meter-h) overflow-hidden bg-primary-hover"
        data-testid="guarantee-meter"
      >
        <div
          className={`h-full transition-[width] duration-(--dh-dur-count) ease-standard ${
            over ? 'bg-attention' : 'bg-[var(--dh-gold)]'
          }`}
          style={{ width: `${filled}%` }}
        />
      </div>

      <p className="mt-3 flex flex-wrap gap-x-4 text-meta leading-snug text-primary-soft">
        <span>
          보증 <Figure>{guaranteed}</Figure>
        </span>
        <span>
          {over ? '초과' : '여유'} <Figure>{difference}</Figure>
        </span>
      </p>
    </>
  )
}

function Figure({ children }: { children: number }) {
  return <span className="font-semibold tabular-nums text-ink-on-accent">{children}</span>
}
