import type { ReactNode } from 'react'
import { useSession } from '../hooks/useSession'
import { useWeddings } from '../hooks/useWeddings'
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
 * 결혼식 이름 IS NOT IN THE SPEC YET AND ITS SLOT IS HERE. `docs/api-spec.md`
 * carries `weddingDate` and `seats` and no `name` (`#212`, backend first), so
 * the slot holds what the spec does have: the couple, read off the two seats.
 * When the field lands it replaces `title` below and nothing else on any of the
 * three screens moves. The rule when the spec is silent is to render what it
 * has, never to guess a field name.
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
    wedding === undefined ? null : wedding.seats.map(seatLabel).join(' · '),
    wedding === undefined ? null : formatWeddingDate(wedding.weddingDate),
    wedding === undefined ? null : daysUntil(wedding.weddingDate, new Date()),
    session.data?.name,
  ]
    .filter((part) => part != null && part !== '')
    .join(' · ')

  return <RunningHead action={action} title={title} />
}

const SEAT_SIDE = { GROOM: '신랑', BRIDE: '신부' } as const

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
