import type { ReactNode } from 'react'
import { useState } from 'react'
import { Link, Navigate } from 'react-router'
import { AddGuestSheet } from '../components/AddGuestSheet'
import { BrandMark } from '../components/BrandMark'
import { buttonClassName } from '../components/Button'
import { GuestRow, LedgerHeader } from '../components/GuestRow'
import { Headcount } from '../components/Headcount'
import { Screen } from '../components/Screen'
import { Slab } from '../components/Slab'
import { FilterChip } from '../components/Tag'
import { WeddingHead } from '../components/WeddingHead'
import { type GuestFilters, useGuests } from '../hooks/useGuests'
import { useWeddings, type Wedding } from '../hooks/useWeddings'
import { pendingInvite } from '../lib/invite'
import { createWeddingPath, invitePath, settingsPath } from '../lib/routes'

/*
 * 하객 명부 — and it is home. There is essentially one screen in this product
 * (notes/2026-08-07-design-screens-and-flow.md): 하객 추가 (`#135`), 하객 상세
 * (`#12`) and 참석 토글 (`#13`) all open on top of this one, and the main action
 * never leaves it. No tab bar and no dashboard, because either creates the
 * question "where do I go to see the number", which this product must not have.
 *
 * IT IS ONE 기물: 보자기 ground → 백자 slab → 자적 굽, with a single 2px gold
 * 구연 at the slab's edge (notes/2026-08-23-decision-the-form-language.md).
 * 인원수 is not a card pinned above the list — it is the face the slab stands
 * on, a rail on a laptop and the slab's head on a phone.
 *
 * THE SCREEN'S ORDER IS 숫자 → 도구 → 목록, and it is the couple's own order:
 * see the number, find the person, tap. 명부와 인원수는 한 화면.
 *
 * THE SCREEN HAS NO TITLE. 결혼식 이름 is a 13px running head above the slab, so
 * the display face appears exactly once — on the headcount. What that head says
 * today is the couple's two seats, because `wedding.name` is `#212` and the
 * spec does not carry it yet; when it does, the name replaces the argument and
 * nothing else here moves.
 *
 * THE WEDDING ID IS NOT IN THE URL. It comes from `GET /weddings`, which holds
 * AT MOST ONE ENTRY — a person belongs to exactly one wedding, and that sentence
 * is what makes `[0]` correct rather than lucky, so a reload lands on the same
 * ledger (docs/api-spec.md § GET /weddings; the list's newest-first order is
 * retained and decides nothing). That also means this screen never calls
 * `GET /weddings/{weddingId}` — the header it would render is already in the
 * list entry, and a second round trip for the same four fields buys nothing.
 */
export function LedgerPage() {
  const weddings = useWeddings()

  /*
   * NEITHER OF THESE WEARS THE LEDGER'S CHROME, and that is deliberate rather
   * than lazy. The app is still deciding which screen this is, so it stays on
   * the screen it was already showing — App renders exactly this while the
   * session resolves — instead of putting up a slab that is about to be torn
   * down and rebuilt under the couple's thumb.
   */
  if (weddings.isPending) {
    return (
      <Screen>
        <BrandMark heading />
      </Screen>
    )
  }

  if (weddings.isError) {
    return (
      <Screen>
        <BrandMark heading />
        <LedgerFailure onRetry={() => void weddings.refetch()} />
      </Screen>
    )
  }

  const wedding = weddings.data[0]

  /*
   * AN EMPTY ARRAY IS NOT AN ERROR — it is the ordinary answer for a person who
   * has no wedding yet, and the branch 최초 1회 exists for. `replace`, so Back
   * from 웨딩 만들기 does not bounce off this screen and return here.
   *
   * AND A PARTNER WHO HAS NOT ACCEPTED YET IS EXACTLY THAT SAME EMPTY ARRAY,
   * which is why 수락 is checked FIRST. Sending them to 웨딩 만들기 and letting
   * them fill it in closes their partner's ledger to them permanently — one
   * person, one wedding, forever (`#158`). The check is one read of
   * `sessionStorage` rather than a rearrangement of routing, because the token
   * came back from Google in this tab
   * (notes/2026-08-22-decision-the-invite-link.md §3).
   *
   * IT IS INSIDE THIS BRANCH AND NOT ABOVE IT. A person who already holds a
   * wedding is never diverted anywhere by a token sitting in storage — 명부 is
   * their screen and it stays on screen.
   */
  if (wedding === undefined) {
    return (
      <Navigate replace to={pendingInvite() === null ? createWeddingPath : invitePath} />
    )
  }

  return <Ledger wedding={wedding} />
}

function Ledger({ wedding }: { wedding: Wedding }) {
  /*
   * THE FILTERS HOLD AT MOST ONE VALUE PER AXIS, which is what makes the request
   * the API refuses unreachable: "both" is not a value, it is the absence of the
   * parameter (docs/api-spec.md § GET /weddings/{weddingId}/guests). Pressing the
   * pressed filter clears its axis; pressing the other one replaces it.
   *
   * `useState` is the bottom rung and this is the only screen that reads it, so
   * it stays here (notes/2026-08-08-decision-frontend-architecture.md).
   *
   * EVERY FILTER UPDATES FUNCTIONALLY. A handler that spreads the `filters` it
   * closed over spreads the value from the render it was created in, and the
   * other axis is carried in that same object — so the question of whether two
   * updates can ever be queued together is one this screen does not have to
   * answer.
   */
  const [filters, setFilters] = useState<GuestFilters>({})
  const guests = useGuests(wedding.id, filters)
  const narrowed = describe(filters)

  /*
   * 하객 추가 opens beside this screen and the main action never leaves it
   * (notes/2026-08-07-design-screens-and-flow.md). Whether the panel is open is
   * this screen's own state and nobody else's, so it stays on the bottom rung —
   * `useState`, here (notes/2026-08-08-decision-frontend-architecture.md).
   */
  const [adding, setAdding] = useState(false)

  /** The states the list itself can be in, and they are exclusive. */
  function list() {
    if (guests.isPending) return <Notice title="하객 명부를 불러오는 중입니다" />
    if (guests.isError) return <LedgerFailure onRetry={() => void guests.refetch()} />

    if (guests.data.length > 0) {
      return (
        /*
         * KEYED ON THE PARTY'S ID, WHICH IS THE HEAD'S — the party's identity,
         * and what keeps a row's own open/closed state with the right team
         * across a refetch and a re-sort (docs/api-spec.md
         * § GET /weddings/{weddingId}/guests).
         */
        <ul aria-busy={guests.isFetching}>
          {guests.data.map((party) => (
            <GuestRow key={party.id} party={party} />
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
    if (guests.isPlaceholderData) return <Notice title="하객 명부를 불러오는 중입니다" />

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
     * screen is two places to press for one action, and the one on the 굽 is
     * always there. No illustration and no emoji: a tool has no reason to be
     * cheerful about being empty.
     */
    return (
      <Notice title="아직 등록된 하객이 없습니다">
        <p className="text-body leading-body text-ink-muted">
          하객 추가로 첫 하객을 등록해 주세요.
        </p>
      </Notice>
    )
  }

  return (
    <Slab
      head={
        <WeddingHead
          action={
            <Link
              className="shrink-0 text-meta text-primary hover:text-primary-hover"
              to={settingsPath}
            >
              설정
            </Link>
          }
        />
      }
    >
      {/*
       * THE NUMBER IS READ BESIDE THE LIST, NOT AFTER IT. Both queries mount in
       * this same commit, so neither waits on the other's response — and neither
       * one's failure decides the other's, because they are two reads of two
       * endpoints (docs/api-spec.md § GET /weddings/{weddingId}/headcount).
       *
       * 하객 추가 LIVES ON THE 굽, which is the one place on this screen that is
       * always visible whether the couple is at the top of the list or the
       * bottom of it. It was a pinned header button; the 굽 is what replaced the
       * pinned header.
       */}
      <Headcount
        action={
          <button
            className={`${buttonClassName('onFoot')} md:w-full`}
            onClick={() => setAdding(true)}
            type="button"
          >
            하객 추가
          </button>
        }
        weddingId={wedding.id}
      />

      <section className="flex min-h-0 min-w-0 flex-1 flex-col">
        {/* 숫자 → 도구 → 목록. The tools row sits below the 44px tap floor on
            purpose: a 44px toolbar between the number and the list pushes the
            list down on the device 명부 is mostly read on
            (notes/2026-08-21-decision-ledger-screen.md). */}
        <div className="flex flex-wrap items-end gap-6 px-4 pt-4 md:px-6 md:pt-6">
          {/* A fieldset, and outside a form on purpose: it is the element that
              carries "these two filters are one axis" without inventing a
              role. */}
          <fieldset aria-label="측" className="flex gap-4">
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
          {/* 참석 여부는 참석 · 불참 둘뿐이다 — there is no 미확인 filter, and
              `?attendance=UNKNOWN` is a 400. */}
          <fieldset aria-label="참석 여부" className="flex gap-4">
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

        <div className="mt-4 flex min-h-0 flex-1 flex-col">
          <LedgerHeader />
          <div className="min-h-0 flex-1 overflow-y-auto">{list()}</div>
        </div>
      </section>

      {adding && (
        <AddGuestSheet onClose={() => setAdding(false)} weddingId={wedding.id} />
      )}
    </Slab>
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
 *
 * A 401 NEVER GETS HERE. It is not a connection problem and 다시 시도 would
 * repeat it forever; the client writes the session to `null` on any 401 and the
 * login screen replaces this one (`lib/queryClient.ts`).
 */
function LedgerFailure({ onRetry }: { onRetry: () => void }) {
  return (
    <Notice title="하객 명부를 불러오지 못했습니다">
      <p className="text-body leading-body text-ink-muted">
        연결을 확인하고 다시 시도해 주세요.
      </p>
      <button className={buttonClassName('secondary')} onClick={onRetry} type="button">
        다시 시도
      </button>
    </Notice>
  )
}

/** The EmptyState part — a bordered block on the slab, never a floating card. */
function Notice({ children, title }: { children?: ReactNode; title: string }) {
  return (
    <div className="m-4 flex max-w-104 flex-col items-start gap-3 border border-line bg-ground px-6 py-8 md:m-6">
      <h2 className="text-lead font-semibold leading-snug">{title}</h2>
      {children}
    </div>
  )
}

/**
 * Pressing a pressed filter clears its axis; pressing the other one replaces it.
 * `undefined` is "both", which is spelled by leaving the parameter out.
 */
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
