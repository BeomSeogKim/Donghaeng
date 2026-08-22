import type { ReactNode } from 'react'
import { useState } from 'react'
import { Link, Navigate } from 'react-router'
import { AddGuestSheet } from '../components/AddGuestSheet'
import { BrandMark } from '../components/BrandMark'
import { buttonClassName } from '../components/Button'
import { GuestRow } from '../components/GuestRow'
import { Headcount } from '../components/Headcount'
import { LogoutButton } from '../components/LogoutButton'
import { Screen } from '../components/Screen'
import { FilterChip } from '../components/Tag'
import { type GuestFilters, useGuests } from '../hooks/useGuests'
import { useWeddings, type Wedding } from '../hooks/useWeddings'
import { createWeddingPath, settingsPath } from '../lib/routes'

/*
 * 원장 — and 원장 is home. There is essentially one screen in this product
 * (notes/2026-08-07-design-screens-and-flow.md): 하객 추가 (`#135`), 하객 상세
 * (`#12`) and 참석 토글 (`#13`) all open on top of this one, and the main action
 * never leaves it. No tab bar and no dashboard, because either creates the
 * question "where do I go to see the number", which this product must not have.
 *
 * THE SCREEN'S ORDER IS 숫자 → 도구 → 목록, and it is the couple's own order:
 * see the number, find the person, tap. 원장과 인원수는 한 화면 — the number is
 * `components/Headcount.tsx` and it is read beside the list, never after it.
 *
 * THE WEDDING ID IS NOT IN THE URL. It comes from `GET /weddings`, whose first
 * entry is contract (newest first), so a reload lands on the same ledger. That
 * also means this screen never calls `GET /weddings/{weddingId}` — the header it
 * would render is already in the list entry, and a second round trip for the
 * same four fields buys nothing. It starts to matter when a wedding id can
 * arrive from outside the list, which v1 has no way to produce.
 */
export function LedgerPage() {
  const weddings = useWeddings()

  /*
   * NEITHER OF THESE WEARS THE LEDGER'S HEADER, and that is deliberate rather
   * than lazy. The app is still deciding which screen this is, so it stays on
   * the screen it was already showing — App renders exactly this while the
   * session resolves — instead of putting up a 원장 header that is about to be
   * torn down and rebuilt under the couple's thumb.
   */
  if (weddings.isPending) {
    return (
      <Screen>
        <BrandMark />
      </Screen>
    )
  }

  if (weddings.isError) {
    return (
      <main className="min-h-[100dvh] bg-ground text-ink">
        <LedgerFailure onRetry={() => void weddings.refetch()} />
      </main>
    )
  }

  const wedding = weddings.data[0]

  // AN EMPTY ARRAY IS NOT AN ERROR — it is the ordinary answer for a person who
  // has no wedding yet, and the branch 최초 1회 exists for. `replace`, so Back
  // from 웨딩 만들기 does not bounce off this screen and return here.
  if (wedding === undefined) return <Navigate replace to={createWeddingPath} />

  return <Ledger wedding={wedding} />
}

function Ledger({ wedding }: { wedding: Wedding }) {
  /*
   * THE FILTERS HOLD AT MOST ONE VALUE PER AXIS, which is what makes the request
   * the API refuses unreachable: "both" is not a value, it is the absence of the
   * parameter (docs/api-spec.md § GET /weddings/{weddingId}/guests). Pressing the
   * pressed chip clears its axis; pressing the other one replaces it.
   *
   * `useState` is the bottom rung and this is the only screen that reads it, so
   * it stays here (notes/2026-08-08-decision-frontend-architecture.md).
   *
   * EVERY CHIP UPDATES FUNCTIONALLY. A handler that spreads the `filters` it
   * closed over spreads the value from the render it was created in, and the
   * other axis is carried in that same object — so the question of whether two
   * updates can ever be queued together is one this screen does not have to
   * answer.
   */
  const [filters, setFilters] = useState<GuestFilters>({})
  const guests = useGuests(wedding.id, filters)
  const narrowed = describe(filters)

  /*
   * 하객 추가 opens OVER this screen and the main action never leaves it
   * (notes/2026-08-07-design-screens-and-flow.md). Whether the sheet is open is
   * this screen's own state and nobody else's, so it stays on the bottom rung —
   * `useState`, here (notes/2026-08-08-decision-frontend-architecture.md).
   */
  const [adding, setAdding] = useState(false)

  /** The states the list itself can be in, and they are exclusive. */
  function list() {
    if (guests.isPending) return <Notice title="원장을 불러오는 중입니다" />
    if (guests.isError) return <LedgerFailure onRetry={() => void guests.refetch()} />

    if (guests.data.length > 0) {
      return (
        <ul
          aria-busy={guests.isFetching}
          className="divide-y divide-line border-y border-line bg-surface"
        >
          {guests.data.map((guest) => (
            <GuestRow guest={guest} key={guest.id} />
          ))}
        </ul>
      )
    }

    /*
     * EMPTY IS ONLY EMPTY ONCE THE SERVER HAS SAID SO ABOUT *THESE* FILTERS.
     * `keepPreviousData` hands back the previous filter's rows while the new
     * request is in flight, and both notices below name the filters that are
     * pressed right now — so without this gate, pressing 신부 straight after a
     * 신랑 that matched nobody would announce "신부측에 아무도 없다" and then
     * contradict itself when the twelve rows land. An instrument does not
     * assert something it has not been told.
     */
    if (guests.isPlaceholderData) return <Notice title="원장을 불러오는 중입니다" />

    if (narrowed !== null) {
      // A filter must never be a dead end: the way out is on the screen that
      // has nothing on it.
      return (
        <Notice title="조건에 맞는 하객이 없습니다">
          <p className="text-body leading-body text-ink-muted">
            {narrowed}으로 좁혀져 있습니다.
          </p>
          <button
            className={buttonClassName('secondary')}
            onClick={() => setFilters({})}
            type="button"
          >
            필터 지우기
          </button>
        </Notice>
      )
    }

    /*
     * Day one for every couple who just made a wedding, so it is a real state
     * rather than an edge case. It names the one action that fills a ledger in
     * v1 — direct entry, the import and the vendor-email paste being post-v1 —
     * and points at the button rather than repeating it: a second 하객 추가 on
     * screen is two places to press for one action, and the pinned one is
     * always there. No illustration and no emoji: a tool has no reason to be
     * cheerful about being empty.
     */
    return (
      <Notice title="아직 등록된 하객이 없습니다">
        <p className="text-body leading-body text-ink-muted">
          위 하객 추가로 첫 하객을 등록해 주세요.
        </p>
      </Notice>
    )
  }

  return (
    <Frame
      /*
       * PINNED WITH THE NUMBER AND THE FILTERS, not in the tools row and not at
       * the bottom of the screen. The bottom belongs to the list, and the
       * filter row is 2rem tall on purpose — a 44px button in it would push the
       * list down on the device 원장 is mostly read on
       * (notes/2026-08-21-decision-ledger-screen.md).
       */
      action={
        <button
          className={buttonClassName('primary')}
          onClick={() => setAdding(true)}
          type="button"
        >
          하객 추가
        </button>
      }
      /*
       * THE NUMBER IS READ BESIDE THE LIST, NOT AFTER IT. Both queries mount in
       * this same commit, so neither waits on the other's response — and neither
       * one's failure decides the other's, because they are two reads of two
       * endpoints (docs/api-spec.md § GET /weddings/{weddingId}/headcount).
       */
      headcount={<Headcount weddingId={wedding.id} />}
      tools={
        <div className="flex flex-wrap items-center gap-2 px-4 pb-3 md:px-6">
          {/* A fieldset, and outside a form on purpose: it is the element that
              carries "these two chips are one axis" without inventing a role. */}
          <fieldset aria-label="측" className="flex gap-2">
            <FilterChip
              onClick={() =>
                setFilters((current) => ({
                  ...current,
                  side: toggle(current.side, 'GROOM'),
                }))
              }
              pressed={filters.side === 'GROOM'}
            >
              신랑
            </FilterChip>
            <FilterChip
              onClick={() =>
                setFilters((current) => ({
                  ...current,
                  side: toggle(current.side, 'BRIDE'),
                }))
              }
              pressed={filters.side === 'BRIDE'}
            >
              신부
            </FilterChip>
          </fieldset>
          {/* 참석 여부는 참석 · 불참 둘뿐이다 — there is no 미확인 chip, and
              `?attendance=UNKNOWN` is a 400. */}
          <fieldset aria-label="참석 여부" className="flex gap-2">
            <FilterChip
              onClick={() =>
                setFilters((current) => ({
                  ...current,
                  attendance: toggle(current.attendance, 'ATTENDING'),
                }))
              }
              pressed={filters.attendance === 'ATTENDING'}
            >
              참석
            </FilterChip>
            <FilterChip
              onClick={() =>
                setFilters((current) => ({
                  ...current,
                  attendance: toggle(current.attendance, 'NOT_ATTENDING'),
                }))
              }
              pressed={filters.attendance === 'NOT_ATTENDING'}
            >
              불참
            </FilterChip>
          </fieldset>
        </div>
      }
      wedding={wedding}
    >
      {list()}
      {adding && (
        <AddGuestSheet onClose={() => setAdding(false)} weddingId={wedding.id} />
      )}
    </Frame>
  )
}

/**
 * The screen around the list. Full-bleed and edge to edge — the ledger is not
 * one of `Screen`'s centered columns.
 *
 * The header and the tools are pinned: the couple must see *which* figure moved
 * when they tap, and they tap while scrolled into the list.
 */
function Frame({
  action,
  children,
  headcount,
  tools,
  wedding,
}: {
  action: ReactNode
  children: ReactNode
  headcount: ReactNode
  tools: ReactNode
  wedding: Wedding
}) {
  return (
    <main className="min-h-[100dvh] bg-ground text-ink">
      <div className="sticky top-0 z-10 border-b border-line bg-ground">
        <header className="flex items-start justify-between gap-4 px-4 py-3 md:px-6">
          <div className="flex min-w-0 flex-col">
            {/* One of RIDIBatang's three places: the headcount, screen titles,
                the brand mark. Never the list. */}
            <h1 className="font-display text-title leading-tight tracking-display">
              원장
            </h1>
            {/* WHOSE LEDGER THIS IS. A person belongs to exactly one wedding
                (docs/api-spec.md § GET /weddings), so this is not a switcher and
                never was — it is the couple, read off the two seats. */}
            <p className="truncate text-meta text-ink-muted">
              {wedding.seats.map(seatLabel).join(' · ')}
            </p>
          </div>
          {/* 하객 추가 KEEPS THE ROW IT IS PRESSED IN, and the two infrequent
              exits share the one below it. Three controls across one row on a
              phone squeeze the couple's own names — the line that says whose
              ledger this is — down to an ellipsis, and 설정 and 로그아웃 are
              both pressed rarely enough to sit under the action rather than
              beside it. 설정 is a link because it is a navigation, the same
              reason starting login is an <a>. */}
          <div className="flex shrink-0 flex-col items-end gap-2">
            {action}
            <div className="flex items-start gap-2">
              <Link className={buttonClassName('secondary')} to={settingsPath}>
                설정
              </Link>
              <LogoutButton className="flex flex-col items-end gap-2 text-right" />
            </div>
          </div>
        </header>

        {/* 인원수 — above the tools and above the list, and inside the pinned
            block with them: the couple taps attendance while scrolled into the
            list and has to see WHICH figure moved. */}
        {headcount}

        {tools}
      </div>

      {children}
    </main>
  )
}

const SEAT_SIDE = { GROOM: '신랑', BRIDE: '신부' } as const

/**
 * What the header calls one seat.
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
function seatLabel(seat: Wedding['seats'][number]): string {
  return seat.name ?? `${SEAT_SIDE[seat.side]} 자리 비어 있음`
}

/**
 * One failure, one message, one way out.
 *
 * 404 IS NOT TOLD APART FROM ANYTHING ELSE. No such wedding, not the caller's,
 * and deleted are one answer on the server, deliberately (docs/api-spec.md) —
 * so turning it into "that wedding does not exist" on the screen would invent an
 * existence hint the API refuses to give. There is nothing to explain and only
 * something to try again.
 *
 * A 401 NEVER GETS HERE. It is not a connection problem and 다시 시도 would
 * repeat it forever; the client writes the session to `null` on any 401 and the
 * login screen replaces this one (`lib/queryClient.ts`).
 */
function LedgerFailure({ onRetry }: { onRetry: () => void }) {
  return (
    <Notice title="원장을 불러오지 못했습니다">
      <p className="text-body leading-body text-ink-muted">
        연결을 확인하고 다시 시도해 주세요.
      </p>
      <button className={buttonClassName('secondary')} onClick={onRetry} type="button">
        다시 시도
      </button>
    </Notice>
  )
}

/** The EmptyState part — a bordered block on surface, never a floating card. */
function Notice({ children, title }: { children?: ReactNode; title: string }) {
  return (
    <div className="mx-4 mt-6 flex max-w-104 flex-col items-start gap-3 border border-line bg-surface px-6 py-8 md:mx-6">
      <h2 className="text-lead font-semibold leading-snug">{title}</h2>
      {children}
    </div>
  )
}

/** Press the pressed chip and its axis clears; press the other and it replaces. */
function toggle<T>(current: T | undefined, pressed: T): T | undefined {
  return current === pressed ? undefined : pressed
}

const SIDE_NARROWED = { GROOM: '신랑측', BRIDE: '신부측' } as const
const ATTENDANCE_NARROWED = { ATTENDING: '참석', NOT_ATTENDING: '불참' } as const

/** What the filters are currently narrowing by, or `null` when they narrow nothing. */
function describe(filters: GuestFilters): string | null {
  const narrowed = [
    filters.side === undefined ? null : SIDE_NARROWED[filters.side],
    filters.attendance === undefined ? null : ATTENDANCE_NARROWED[filters.attendance],
  ].filter((label) => label !== null)

  return narrowed.length === 0 ? null : narrowed.join(' · ')
}
