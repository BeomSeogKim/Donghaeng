import { useHeadcount } from '../hooks/useHeadcount'
import { buttonClassName } from './Button'

/*
 * 인원수 — the Stat part (design/components/parts/15-stat.html), pinned to the
 * top of 원장.
 *
 * IT IS THE SAME SCREEN AS THE LEDGER, and that is the product's first fixed
 * point: tapping attendance moves this number in place, so it is inside the
 * sticky header rather than at the top of the scroll. The couple has to see
 * *which* figure moved.
 *
 * TWO NUMBERS, AND THERE IS NO THIRD. 식대 인원 and 보증인원. There is no 미확인
 * count — 참석 여부는 참석·불참 둘뿐이고, the "아직 모르는 N명" of the original
 * design record is withdrawn (notes/2026-08-21-decision-attendance-is-two-states.md).
 * And there is no recommendation, no percentage and no "you are 12 over" beyond
 * the subtraction below: 보증인원 is the venue's number, never ours.
 *
 * NO CARD BORDER, unlike the part's preview. The part draws a bordered block
 * because a gallery card needs an edge; here the pinned header already has one
 * and a bordered block inside a bordered bar is a box in a box. Everything the
 * part actually decides — the display face, the 40px figure in 자적, the meter,
 * the 260ms settle — is unchanged.
 *
 * ONE OF RIDIBatang's THREE PLACES: the headcount, screen titles, the brand
 * mark. Never the list. And every digit here is tabular, because a number whose
 * width shifts as it counts reads as unstable — 정직함·믿음직함 in typography.
 */
export function Headcount({ weddingId }: { weddingId: number }) {
  const headcount = useHeadcount(weddingId)

  /*
   * A NUMBER THE SERVER DID NOT JUST CONFIRM IS NOT A NUMBER. React Query keeps
   * the last successful data through a failed refetch — and a refetch is
   * ordinary here, because `staleTime` is 0 and the window regains focus — so
   * without this the couple would be shown a 40px figure from some earlier
   * moment, at full confidence, with a 13px note beside it. The list does the
   * same thing for the same reason: its failure replaces the rows it was
   * holding rather than sitting under them.
   */
  const counted = headcount.isError ? undefined : headcount.data
  const meal = counted?.mealHeadcount
  const guaranteed = counted?.guaranteedHeadcount

  return (
    <section
      aria-busy={headcount.isPending}
      aria-label="인원수"
      className="px-4 pb-3 md:px-6"
    >
      {/*
       * ink-muted, not the part's ink-faint. This label is what says which
       * number the couple is looking at, so it is text rather than decoration,
       * and ink-faint is 3.2:1 — decorative and disabled only (design/AGENTS.md).
       */}
      <p className="text-meta leading-snug text-ink-muted">식대 인원</p>

      <p className="mt-0.5 flex items-baseline gap-1.5">
        {/*
         * AN UNCOUNTED NUMBER IS NEVER DRAWN AS 0. An empty ledger genuinely is
         * 0 and says so; a read that is still in flight or that failed has no
         * number at all, and the two may not look the same on the one screen
         * whose claim is that its numbers are never wrong. The dash holds the
         * position so nothing jumps when the number lands.
         */}
        <span className="font-display text-display font-bold leading-tight tracking-display tabular-nums text-primary">
          {meal ?? '—'}
        </span>
        <span className="text-lead font-medium leading-snug text-ink-muted">명</span>
      </p>

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
        <div className="mt-2 flex flex-wrap items-center gap-3">
          <p className="text-meta leading-snug text-ink-muted">
            인원수를 불러오지 못했습니다.
          </p>
          <button
            className={buttonClassName('secondary')}
            onClick={() => void headcount.refetch()}
            type="button"
          >
            다시 시도
          </button>
        </div>
      )}
    </section>
  )
}

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
      <p className="mt-2 flex flex-wrap gap-x-3 text-meta leading-snug text-ink-muted">
        <span>
          보증 <Figure>{guaranteed}</Figure>
        </span>
        <span className={over ? 'text-attention' : undefined}>
          {over ? '초과' : '여유'} <Figure over={over}>{difference}</Figure>
        </span>
      </p>

      {/*
       * The meter says the same thing the two numbers above already said, at a
       * glance and mid-scroll — that is what it is for, and it is why it carries
       * no text of its own and is hidden from the screen reader rather than
       * announced twice. It settles over 260ms, deliberately slower than the
       * 120ms chip, so the couple can see which number moved.
       */}
      <div
        aria-hidden="true"
        className="mt-3 h-1 overflow-hidden bg-surface-sunken"
        data-testid="guarantee-meter"
      >
        <div
          className={`h-full transition-[width] duration-(--dh-dur-count) ease-standard ${over ? 'bg-attention' : 'bg-primary'}`}
          style={{ width: `${filled}%` }}
        />
      </div>
    </>
  )
}

function Figure({ children, over = false }: { children: number; over?: boolean }) {
  return (
    <span
      className={`font-semibold tabular-nums ${over ? 'text-attention' : 'text-ink'}`}
    >
      {children}
    </span>
  )
}
