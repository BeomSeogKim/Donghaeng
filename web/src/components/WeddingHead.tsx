import type { ReactNode } from 'react'
import { useSession } from '../hooks/useSession'
import { useWeddings, type Wedding } from '../hooks/useWeddings'
import { SEAT_SIDE } from '../lib/seat'
import { daysUntil, formatWeddingDate } from '../lib/weddingDate'
import { RunningHead } from './Slab'

/*
 * 결혼식 이름 · 예식일 · D-N · 내 이름 — the 13px line above every slab a
 * signed-in couple sees.
 *
 * IT IS ONE COMPONENT FOR THREE SCREENS. 하객 명부, 설정 and 마이페이지 all wear
 * it, and three copies of one line is three lines that drift — this is exactly
 * what `components/SubScreen` was made for when 마이페이지 turned out to be
 * wearing 설정's header by hand.
 *
 * 결혼식 이름 TOOK THE SLOT THAT WAS LEFT FOR IT (`#212`), and nothing else on
 * any of the three screens moved. It is the couple's own words for their own
 * wedding, so it says more than the two names do — and it says it on the day
 * the second seat is still empty, which is most of the days a ledger is used.
 *
 * THE COUPLE IS WHAT IS THERE UNTIL THE NAME IS. `weddingName` is `null` for a
 * wedding nobody has named, which is an ordinary wedding rather than an error,
 * and **what the head renders in that case is ours to decide — the API has no
 * copy for it** (docs/api-spec.md § GET /weddings/{weddingId}). So the slot
 * falls back to what it held before: the two seats. A placeholder would be a
 * line about a feature; the seats are a line about this couple.
 *
 * D-N IS COMPUTED HERE AND IT IS NOT AN AGGREGATE. Calendar arithmetic on a
 * date the API already published, pinned to Seoul so two phones cannot disagree
 * about it (`lib/weddingDate.ts`). There is no endpoint for it to contradict.
 *
 * IT RENDERS WHAT IT HAS AND NEVER A PLACEHOLDER. Both reads are almost always
 * already in the cache — the ledger opened them — and on a cold deep link into
 * 설정 the line simply fills in. A skeleton in a 13px running head is motion
 * spent on apparatus.
 */
export function WeddingHead({ action }: { action: ReactNode }) {
  const weddings = useWeddings()
  const session = useSession()
  const wedding = weddings.data?.[0]

  const title = [
    wedding === undefined ? null : weddingLabel(wedding),
    wedding === undefined ? null : formatWeddingDate(wedding.weddingDate),
    wedding === undefined ? null : daysUntil(wedding.weddingDate, new Date()),
    session.data?.name,
  ]
    .filter((part) => part != null && part !== '')
    .join(' · ')

  return <RunningHead action={action} title={title} />
}

/**
 * What the running head calls this wedding: its name, or the couple in it.
 *
 * IT IS ONE SLOT AND NEVER BOTH. Putting the name beside the two seats would
 * spend the head's whole width on things the couple already knows, and the head
 * truncates — 예식일, D-N and 내 이름 are what would fall off the end.
 */
function weddingLabel(wedding: Wedding): string {
  return wedding.weddingName ?? wedding.seats.map(seatLabel).join(' · ')
}

/**
 * What the running head calls one seat.
 *
 * SHOW WHAT YOU HAVE. A seat whose person has not arrived is the ordinary state
 * of a wedding on its first day — both seats are created with the wedding and
 * the partner's carries a side and nothing else (docs/api-spec.md
 * § POST /weddings). So the empty half is stated as the fact it is, in the same
 * neutral line as the names, rather than rendered as a gap, a dash, or an error.
 *
 * ONE `??` COVERS BOTH ABSENCES, and that is not defensiveness about the API.
 * The document types `name` as optional AND nullable because springdoc leaves a
 * nullable Kotlin property out of `required`; the API always sends the key, with
 * `null` in it. The written contract is the narrower of the two, and this reads
 * the same on either.
 */
function seatLabel(seat: { side: 'GROOM' | 'BRIDE'; name?: string | null }): string {
  return seat.name ?? `${SEAT_SIDE[seat.side]} 자리 비어 있음`
}
