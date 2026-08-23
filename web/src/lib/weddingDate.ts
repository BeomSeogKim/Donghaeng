/*
 * 예식일, as the running head says it — and the countdown beside it.
 *
 * THIS IS THE ONE NUMBER ON SCREEN THE SERVER DOES NOT SEND, and it is worth
 * saying why that is not the rule being broken. "All computation is
 * server-side" exists so an aggregate can never differ between two places; a
 * D-day is not an aggregate, it is calendar arithmetic on a single field the
 * API already published, and there is no endpoint it could disagree with.
 *
 * THE TIME ZONE IS PINNED TO SEOUL, and that is the correctness decision here
 * rather than a nicety. A wedding date is a calendar date with no time and no
 * zone; "how many days away is it" has no answer at all until you say whose
 * midnight counts. Read off the device clock, a couple checking the ledger from
 * a work trip would be shown a different D-day than their partner at home —
 * off by one, on the screen this product asks to be trusted for its numbers.
 * The wedding is in Korea, so Korea's midnight is the one that counts.
 */

const SEOUL = 'Asia/Seoul'

/** `2026. 10. 17. (토)` — the running head's spelling. */
export function formatWeddingDate(isoDate: string): string {
  const date = parse(isoDate)
  if (date === null) return isoDate

  const day = new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    timeZone: 'UTC',
  }).format(date)
  const weekday = new Intl.DateTimeFormat('ko-KR', {
    weekday: 'short',
    timeZone: 'UTC',
  }).format(date)

  return `${day} (${weekday})`
}

/**
 * `D-55`, `D-DAY`, `D+3` — or `null` for a date that cannot be read.
 *
 * `today` is a parameter rather than a call to `Date.now()` inside, because a
 * countdown that cannot be tested at a fixed moment is a countdown nobody has
 * checked the boundary of. The caller passes the real clock.
 */
export function daysUntil(isoDate: string, today: Date): string | null {
  const wedding = parse(isoDate)
  if (wedding === null) return null

  const now = parse(seoulToday(today))
  if (now === null) return null

  // Both are UTC midnights of a calendar date, so the difference is exact days
  // with no daylight-saving remainder to round.
  const days = Math.round((wedding.getTime() - now.getTime()) / 86_400_000)
  if (days === 0) return 'D-DAY'
  return days > 0 ? `D-${days}` : `D+${-days}`
}

/**
 * `YYYY-MM-DD` as a UTC midnight, or `null`.
 *
 * UTC, not local: the API sends a calendar date, and constructing it in the
 * device's zone makes the same string a different instant on two phones.
 */
function parse(isoDate: string): Date | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(isoDate)) return null
  const date = new Date(`${isoDate}T00:00:00Z`)
  return Number.isNaN(date.getTime()) ? null : date
}

/** What day it is in Seoul, as `YYYY-MM-DD`. */
function seoulToday(now: Date): string {
  // `en-CA` is the one common locale whose numeric date format IS `YYYY-MM-DD`,
  // which is why it is here rather than a hand-assembled string: the parts come
  // out of the same formatter that applied the time zone.
  return new Intl.DateTimeFormat('en-CA', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    timeZone: SEOUL,
  }).format(now)
}
