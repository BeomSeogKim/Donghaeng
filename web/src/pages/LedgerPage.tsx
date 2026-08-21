import type { ReactNode } from 'react'
import { useState } from 'react'
import { Navigate } from 'react-router'
import { BrandMark } from '../components/BrandMark'
import { buttonClassName } from '../components/Button'
import { GuestRow } from '../components/GuestRow'
import { Screen } from '../components/Screen'
import { FilterChip } from '../components/Tag'
import { type GuestFilters, useGuests } from '../hooks/useGuests'
import { useLogout } from '../hooks/useLogout'
import { useWeddings, type Wedding } from '../hooks/useWeddings'
import { createWeddingPath } from '../lib/routes'

/*
 * 원장 — and 원장 is home. There is essentially one screen in this product
 * (notes/2026-08-07-design-screens-and-flow.md): 하객 추가 (`#135`), 하객 상세
 * (`#12`) and 참석 토글 (`#13`) all open on top of this one, and the main action
 * never leaves it. No tab bar and no dashboard, because either creates the
 * question "where do I go to see the number", which this product must not have.
 *
 * THE SCREEN'S ORDER IS 숫자 → 도구 → 목록, and it is the couple's own order:
 * see the number, find the person, tap. The number is `#17` and is not built —
 * see the slot below, which is left empty rather than filled with a placeholder.
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
   */
  const [filters, setFilters] = useState<GuestFilters>({})
  const guests = useGuests(wedding.id, filters)
  const narrowed = describe(filters)

  /** The four states the list itself can be in, and they are exclusive. */
  function list() {
    if (guests.isPending) return <Notice title="원장을 불러오는 중입니다" />
    if (guests.isError) return <LedgerFailure onRetry={() => void guests.refetch()} />

    if (guests.data.length === 0 && narrowed !== null) {
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

    if (guests.data.length === 0) {
      /*
       * Day one for every couple who just made a wedding, so it is a real state
       * rather than an edge case. It says what it can honestly say and no more:
       * 하객 추가 (`#135`), the file import and the vendor-email paste are the
       * actions that fill a ledger, and none of them exists yet — an empty
       * screen offering a button that does nothing would be worse than one that
       * admits it. No illustration and no emoji: a tool has no reason to be
       * cheerful about being empty.
       */
      return (
        <Notice title="아직 등록된 하객이 없습니다">
          <p className="text-body leading-body text-ink-muted">
            하객을 추가하는 화면은 아직 준비 중입니다.
          </p>
        </Notice>
      )
    }

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

  return (
    <Frame
      tools={
        <div className="flex flex-wrap items-center gap-2 px-4 pb-3 md:px-6">
          {/* A fieldset, and outside a form on purpose: it is the element that
              carries "these two chips are one axis" without inventing a role. */}
          <fieldset aria-label="측" className="flex gap-2">
            <FilterChip
              onClick={() =>
                setFilters({ ...filters, side: toggle(filters.side, 'GROOM') })
              }
              pressed={filters.side === 'GROOM'}
            >
              신랑
            </FilterChip>
            <FilterChip
              onClick={() =>
                setFilters({ ...filters, side: toggle(filters.side, 'BRIDE') })
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
                setFilters({
                  ...filters,
                  attendance: toggle(filters.attendance, 'ATTENDING'),
                })
              }
              pressed={filters.attendance === 'ATTENDING'}
            >
              참석
            </FilterChip>
            <FilterChip
              onClick={() =>
                setFilters({
                  ...filters,
                  attendance: toggle(filters.attendance, 'NOT_ATTENDING'),
                })
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
  children,
  tools,
  wedding,
}: {
  children: ReactNode
  tools: ReactNode
  wedding: Wedding
}) {
  const logout = useLogout()

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
            {/* Which ledger this is. One person may belong to several weddings,
                and v1 has no way to switch between them, so saying which one is
                open is the whole of what this screen can honestly do about it. */}
            <p className="truncate text-meta text-ink-muted">
              {wedding.groomName} · {wedding.brideName}
            </p>
          </div>
          <button
            className={buttonClassName('secondary')}
            disabled={logout.isPending}
            onClick={() => logout.mutate()}
            type="button"
          >
            로그아웃
          </button>
        </header>

        {/* 인원수 — `#17` — GOES HERE, above the tools and above the list, and
            nothing stands in for it in the meantime. A placeholder number on
            this screen is a wrong number, and a skeleton is a promise that a
            number is on its way when no endpoint answers it yet. */}

        {tools}
      </div>

      {children}
    </main>
  )
}

/**
 * One failure, one message, one way out.
 *
 * 404 IS NOT TOLD APART FROM ANYTHING ELSE. No such wedding, not the caller's,
 * and deleted are one answer on the server, deliberately (docs/api-spec.md) —
 * so turning it into "that wedding does not exist" on the screen would invent an
 * existence hint the API refuses to give. There is nothing to explain and only
 * something to try again.
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
