import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { expect, it } from 'vitest'
import { App } from '../App'
import {
  type Guest,
  type GuestParty,
  guestsQueryKey,
  ledgerQueryKey,
} from '../hooks/useGuests'
import { type Headcount, headcountQueryKey } from '../hooks/useHeadcount'
import type { Session } from '../hooks/useSession'
import type { Wedding } from '../hooks/useWeddings'
import { INVITE_STORAGE_KEY } from '../lib/invite'
import { renderWithProviders } from '../test/render'
import { server } from '../test/server'

/*
 * 원장 — the ledger, end to end inside the app, with only the network faked.
 *
 * These are mandatory tests, not optional ones: the ledger display is named in
 * notes/2026-08-08-decision-frontend-testing-methodology.md, and the request
 * this screen builds is the one the API refuses outright when it is built
 * wrongly. `?side=GROOM&side=BRIDE` is a 400 — deliberately, because a repeated
 * parameter binds to its first value, so unrefused it would answer 신랑측 only
 * and look like a perfectly ordinary ledger (docs/api-spec.md
 * § GET /weddings/{weddingId}/guests). So the assertions below are on the URL
 * that reached the server, not on the state of the chips.
 */

const API = 'http://localhost:8080'

const WEDDING: Wedding = {
  id: 12,
  weddingDate: '2026-10-10',
  seats: [
    { side: 'GROOM', name: '김신랑' },
    { side: 'BRIDE', name: '이신부' },
  ],
}

/** The ordinary state of a new wedding: one seat taken, the other waiting. */
const HALF_SEATED: Wedding = {
  ...WEDDING,
  seats: [
    { side: 'GROOM', name: '김신랑' },
    { side: 'BRIDE', name: null },
  ],
}

/** One person, as the API returns one inside a party. */
function person(id: number, name: string, overrides: Partial<Guest> = {}): Guest {
  return {
    id,
    name,
    side: 'GROOM',
    groupCategory: 'OTHER',
    groupLabel: null,
    contact: null,
    accessibilityNote: null,
    expectedAttending: true,
    companionOf: null,
    ...overrides,
  }
}

/**
 * A party, folded exactly as the server folds it (docs/api-spec.md
 * § GET /weddings/{weddingId}/guests).
 *
 * `size` AND `attendingCount` ARE COUNTED FROM THE MEMBERS HANDED IN, never
 * passed separately. The server counts what it returned, and a double that
 * could disagree with its own `members` would let a screen showing a wrong
 * 참석 column stay green — on the product whose one claim is that its numbers
 * are not wrong.
 */
function party(head: Guest, ...companions: readonly Guest[]): GuestParty {
  const members = [head, ...companions]
  return {
    // The head's, always — even under a filter that excluded the head.
    id: head.id,
    name: head.name,
    size: members.length,
    attendingCount: members.filter((member) => member.expectedAttending).length,
    members,
  }
}

/**
 * A 동반, named the way the server names one — `{대표자} 동반 N`, N from 1 —
 * and carrying the head's 측 · 그룹 · 라벨 · 참석 as the defaults creation
 * applies. Each of those is overridable, because after creation every one of
 * them moves independently and that is the point of `#213`.
 *
 * The id is `head.id * 10 + N`, so a companion of head 1 cannot be mistaken for
 * head 2 in a test that holds both.
 */
function companion(head: Guest, nth: number, overrides: Partial<Guest> = {}): Guest {
  return person(head.id * 10 + nth, `${head.name} 동반 ${nth}`, {
    side: head.side,
    groupCategory: head.groupCategory,
    groupLabel: head.groupLabel,
    expectedAttending: head.expectedAttending,
    ...overrides,
    companionOf: head.id,
  })
}

/** A party of one — one person, and the ordinary row of most ledgers. */
function guest(id: number, name: string, overrides: Partial<Guest> = {}): GuestParty {
  return party(person(id, name, overrides))
}

/**
 * The four calls this screen makes, with every ledger request kept in the order
 * it arrived — the URL included, which is the whole point — and every headcount
 * request counted, because "it is not asked again after a mutation" is a count.
 */
function api() {
  const ledgerRequests: URL[] = []
  const headcountRequests: URL[] = []
  return {
    ledgerRequests,
    headcountRequests,
    /** The last ledger URL the server was asked for. */
    lastLedgerRequest: () => ledgerRequests[ledgerRequests.length - 1],
    me: () =>
      http.get(`${API}/auth/me`, () =>
        HttpResponse.json<Session>({ id: 7, name: '김테스터' }),
      ),
    weddings: (respond: () => Response = () => HttpResponse.json<Wedding[]>([WEDDING])) =>
      http.get(`${API}/weddings`, () => respond()),
    guests: (respond: (url: URL) => Response | Promise<Response>) =>
      http.get(`${API}/weddings/:weddingId/guests`, async ({ request }) => {
        const url = new URL(request.url)
        ledgerRequests.push(url)
        return await respond(url)
      }),
    headcount: (respond: () => Response | Promise<Response> = counted(0)) =>
      http.get(`${API}/weddings/:weddingId/headcount`, async ({ request }) => {
        headcountRequests.push(new URL(request.url))
        return await respond()
      }),
  }
}

/**
 * The number the server would have computed for these guests.
 *
 * Every double below sums to the rows it is handed — a constant beside a list it
 * contradicts is a double the screen could pass while the real screen shows a
 * wrong number. `guaranteedHeadcount` is passed only where a test is about it:
 * the server omits the member until the couple has agreed one with their venue,
 * which is every couple in v1 (docs/api-spec.md § GET .../headcount).
 */
function counted(mealHeadcount: number, guaranteedHeadcount?: number) {
  return () =>
    HttpResponse.json<Headcount>(
      guaranteedHeadcount === undefined
        ? { mealHeadcount }
        : { mealHeadcount, guaranteedHeadcount },
    )
}

const problem = (status: number, code: string) =>
  HttpResponse.json(
    {
      type: 'about:blank',
      title: 'Error',
      status,
      instance: '/weddings/12/guests',
      code,
    },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )

/** The rendered guest names, in the order the screen put them in. */
function renderedNames() {
  return screen.getAllByTestId('guest-name').map((element) => element.textContent ?? '')
}

it('renders the wedding it took from GET /weddings, in 이름 가나다순', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    // Entry order — the API's contract, and deliberately not 가나다순.
    calls.guests(() =>
      HttpResponse.json<GuestParty[]>([
        guest(1, '한지우'),
        guest(2, '박지민'),
        guest(3, '김영수'),
        guest(4, '이서연'),
      ]),
    ),
    calls.headcount(counted(4)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  await screen.findAllByTestId('guest-name')
  // 이름 검색 is post-v1, so the order IS how a couple finds a person in v1.
  expect(renderedNames()).toEqual(['김영수', '박지민', '이서연', '한지우'])
  // Whose ledger this is — both seats taken, so both names. It is the running
  // head's first field, which is where 결혼식 이름 goes when `#212` lands.
  expect(screen.getByText(/김신랑 · 이신부/)).toBeVisible()
  // `[0]` of GET /weddings, which is newest first and is contract.
  expect(calls.lastLedgerRequest().pathname).toBe('/weddings/12/guests')
})

it('offers 하객 추가 and 설정 in the header, and 로그아웃 nowhere on the ledger', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() => HttpResponse.json<GuestParty[]>([])),
    calls.headcount(),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  expect(await screen.findByRole('button', { name: '하객 추가' })).toBeVisible()
  expect(screen.getByRole('link', { name: '설정' })).toBeVisible()
  // 로그아웃 LEFT THIS SCREEN. The second header row it stood in lives inside
  // `sticky top-0`, so it spent vertical space above the number and the filters
  // on every scroll of the ledger, for an action taken about once a session
  // (notes/2026-08-22-decision-logout-leaves-the-ledger.md). It is on 마이페이지
  // now, two taps away through 설정.
  expect(screen.queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument()
})

it('names the empty seat rather than leaving a gap where a name would be', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(() => HttpResponse.json<Wedding[]>([HALF_SEATED])),
    calls.guests(() => HttpResponse.json<GuestParty[]>([])),
    calls.headcount(),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  // A wedding one partner made alone is EVERY wedding on its first day, and the
  // partner's seat is created empty on purpose (docs/api-spec.md
  // § GET /weddings/{weddingId}). So this is the ordinary state, not a failure —
  // the header states it and does not dress it up as an error.
  expect(await screen.findByText(/김신랑 · 신부 자리 비어 있음/)).toBeVisible()
  // The two ways an absent name leaks onto a screen: the literal and the blank.
  expect(document.body.textContent).not.toContain('null')
  expect(screen.queryByText(/김신랑 · ·/)).not.toBeInTheDocument()
})

it('sends no filter parameter at all while both sides and both answers are wanted', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() => HttpResponse.json<GuestParty[]>([])),
    calls.headcount(),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  await waitFor(() => expect(calls.ledgerRequests).toHaveLength(1))
  // "Both" is spelled by leaving the filter out. There is no value that says it.
  expect(calls.lastLedgerRequest().search).toBe('')
})

it('narrows to one 측 with a single parameter, and never repeats it', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() => HttpResponse.json<GuestParty[]>([])),
    calls.headcount(),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  await waitFor(() => expect(calls.ledgerRequests).toHaveLength(1))

  await userEvent.click(screen.getByRole('button', { name: '신랑' }))

  await waitFor(() => expect(calls.ledgerRequests).toHaveLength(2))
  expect(calls.lastLedgerRequest().searchParams.getAll('side')).toEqual(['GROOM'])

  // The other chip of the same axis REPLACES it. Two chips of one axis pressed
  // at once is not a state this screen can be in, which is what makes the 400
  // unreachable rather than merely avoided.
  await userEvent.click(screen.getByRole('button', { name: '신부' }))

  await waitFor(() => expect(calls.ledgerRequests).toHaveLength(3))
  expect(calls.lastLedgerRequest().searchParams.getAll('side')).toEqual(['BRIDE'])

  // Pressing the pressed chip clears the filter — back to both sides, which is
  // again spelled by sending nothing.
  await userEvent.click(screen.getByRole('button', { name: '신부' }))

  await waitFor(() => expect(calls.ledgerRequests).toHaveLength(4))
  expect(calls.lastLedgerRequest().search).toBe('')
})

it('carries both axes at once, each exactly once', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() => HttpResponse.json<GuestParty[]>([])),
    calls.headcount(),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  await waitFor(() => expect(calls.ledgerRequests).toHaveLength(1))

  await userEvent.click(screen.getByRole('button', { name: '신랑' }))
  await userEvent.click(screen.getByRole('button', { name: '불참' }))

  await waitFor(() => expect(calls.ledgerRequests).toHaveLength(3))
  const last = calls.lastLedgerRequest()
  expect(last.searchParams.getAll('side')).toEqual(['GROOM'])
  expect(last.searchParams.getAll('attendance')).toEqual(['NOT_ATTENDING'])
  // 미확인 is not a third value of this axis and `?attendance=UNKNOWN` is a 400.
  expect([...last.searchParams.keys()]).toEqual(['side', 'attendance'])
})

it('says the ledger is empty when nobody has been entered yet', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() => HttpResponse.json<GuestParty[]>([])),
    calls.headcount(),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  // An empty ledger is a 200 and `[]`, never a 404 — and it is day one for
  // every couple who just made a wedding.
  expect(await screen.findByText('아직 등록된 하객이 없습니다')).toBeVisible()
})

it('never leaves a filter at a dead end', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests((url) =>
      HttpResponse.json<GuestParty[]>(
        url.search === '' ? [guest(1, '김영수', { side: 'GROOM' })] : [],
      ),
    ),
    calls.headcount(counted(1)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  await screen.findByTestId('guest-name')

  await userEvent.click(screen.getByRole('button', { name: '신부' }))

  expect(await screen.findByText('조건에 맞는 하객이 없습니다')).toBeVisible()
  // Which filters are narrowing it, said out loud — an empty list with no
  // explanation reads as a lost ledger.
  expect(screen.getByText('신부측으로 좁혀져 있습니다.')).toBeVisible()

  await userEvent.click(screen.getByRole('button', { name: '필터 지우기' }))

  expect(await screen.findByTestId('guest-name')).toHaveTextContent('김영수')
  expect(calls.lastLedgerRequest().search).toBe('')
  expect(screen.getByRole('button', { name: '신부' })).toHaveAttribute(
    'aria-pressed',
    'false',
  )
})

/** 혼주 손님 with one companion — the party this test reads the columns off. */
const KIM = person(1, '김영수', {
  side: 'GROOM',
  groupCategory: 'PARENTS_GUEST',
  groupLabel: '아버지 회사 동료',
})

it('shows each guest with the side, group and party size the ledger is read by', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() =>
      HttpResponse.json<GuestParty[]>([
        party(
          KIM,
          companion(KIM, 1),
          // The 인원 column is the party's size, so it is the row's people and
          // no longer a number somebody typed into a column.
        ),
        guest(2, '윤채원', {
          side: 'BRIDE',
          groupCategory: 'FRIEND',
          expectedAttending: false,
        }),
      ]),
    ),
    calls.headcount(counted(2)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  const rows = await screen.findAllByRole('listitem')
  expect(within(rows[0]).getByText('신랑')).toBeVisible()
  expect(within(rows[0]).getByText('혼주 손님')).toBeVisible()
  expect(within(rows[0]).getByText('아버지 회사 동료')).toBeVisible()
  // 인원 is a bare figure under its column heading — the unit is for the screen
  // reader, so the badge's `2명` is gone with the badge.
  expect(within(rows[0]).getByText(reads('2명'))).toBeVisible()
  expect(within(rows[0]).getByText('참석')).toBeVisible()
  // 불참 is a fact, not an error — it is stated as plainly as 참석.
  expect(within(rows[1]).getByText('불참')).toBeVisible()
})

it('offers the failure again rather than explaining it away', async () => {
  const calls = api()
  let attempt = 0
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() => {
      attempt += 1
      return attempt === 1
        ? problem(500, 'INTERNAL_ERROR')
        : HttpResponse.json<GuestParty[]>([guest(1, '김영수')])
    }),
    calls.headcount(counted(1)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  expect(await screen.findByText('하객 명부를 불러오지 못했습니다')).toBeVisible()

  await userEvent.click(screen.getByRole('button', { name: '다시 시도' }))

  expect(await screen.findByTestId('guest-name')).toHaveTextContent('김영수')
})

it('turns a 404 into the same failure as any other, never into an existence hint', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() => problem(404, 'WEDDING_NOT_FOUND')),
    calls.headcount(() => problem(404, 'WEDDING_NOT_FOUND')),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  // No such wedding, not the caller's, and deleted are one answer on the server
  // (docs/api-spec.md), so they are one answer on the screen too.
  expect(await screen.findByText('하객 명부를 불러오지 못했습니다')).toBeVisible()
  expect(screen.queryByText(/없는 웨딩|권한/)).not.toBeInTheDocument()
})

it('reads a failed wedding list as the same failure, and recovers from it', async () => {
  const calls = api()
  let attempt = 0
  server.use(
    calls.me(),
    calls.weddings(() => {
      attempt += 1
      return attempt === 1
        ? problem(500, 'INTERNAL_ERROR')
        : HttpResponse.json<Wedding[]>([WEDDING])
    }),
    calls.guests(() => HttpResponse.json<GuestParty[]>([guest(1, '김영수')])),
    calls.headcount(counted(1)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  // Which of the two reads failed is not the couple's problem: they asked for
  // 원장 and did not get it. One message, one way out.
  expect(await screen.findByText('하객 명부를 불러오지 못했습니다')).toBeVisible()

  await userEvent.click(screen.getByRole('button', { name: '다시 시도' }))

  expect(await screen.findByTestId('guest-name')).toHaveTextContent('김영수')
})

it('sends a person with no wedding to 웨딩 만들기 instead of an empty ledger', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(() => HttpResponse.json<Wedding[]>([])),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  // An empty array is the ordinary answer for someone with no wedding, and it
  // is the branch 최초 1회 exists for — not an error and not an empty ledger.
  expect(await screen.findByRole('heading', { name: '결혼식 만들기' })).toBeVisible()
  expect(calls.ledgerRequests).toHaveLength(0)
})

it('sends a partner holding an invite to 수락, never to 웨딩 만들기', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(() => HttpResponse.json<Wedding[]>([])),
  )
  // The tab came back from Google still holding what it stashed before it left
  // (lib/invite.ts).
  sessionStorage.setItem(INVITE_STORAGE_KEY, 'sel3ct0r.v3r1f13r')

  renderWithProviders(<App />, { initialEntries: ['/'] })

  /*
   * A PARTNER WHO HAS NOT ACCEPTED YET *IS* AN EMPTY `GET /weddings`, which is
   * why this check has to stand in front of the empty-list branch and not
   * beside it. 웨딩 만들기 is where an empty list otherwise sends people, and
   * creating there closes their partner's ledger to them permanently — one
   * person, one wedding, forever (`#158`,
   * notes/2026-08-22-decision-the-invite-link.md §3).
   */
  expect(await screen.findByRole('heading', { name: '초대를 받았습니다' })).toBeVisible()
  expect(screen.queryByRole('heading', { name: '결혼식 만들기' })).not.toBeInTheDocument()
})

/*
 * `keepPreviousData` keeps the previous filter's rows on screen while the next
 * request is in flight, and that is deliberate — the list is not the headcount,
 * and blanking it on every chip is the screen changing its mind in front of the
 * couple. What it must not do is let a *notice* speak for rows it is only
 * holding: both empty notices name the filters that are pressed right now, and
 * the rows behind them belong to the filters that were pressed a moment ago.
 */

it('does not say a filter matched nobody before the server has answered it', async () => {
  const calls = api()
  let release = () => {}
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(async (url) => {
      // 신부측 has twelve people and is slow; everything else has nobody.
      if (url.searchParams.get('side') !== 'BRIDE')
        return HttpResponse.json<GuestParty[]>([])
      await held
      return HttpResponse.json<GuestParty[]>([guest(1, '윤채원', { side: 'BRIDE' })])
    }),
    calls.headcount(counted(1)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  await screen.findByText('아직 등록된 하객이 없습니다')

  await userEvent.click(screen.getByRole('button', { name: '신랑' }))
  expect(await screen.findByText('신랑측으로 좁혀져 있습니다.')).toBeVisible()

  await userEvent.click(screen.getByRole('button', { name: '신부' }))

  // The rows underneath are still 신랑's zero rows. Nothing has said anything
  // about 신부측 yet, so the screen may not either — it said so instantly, and
  // then contradicted itself when the twelve landed.
  expect(screen.queryByText('신부측으로 좁혀져 있습니다.')).not.toBeInTheDocument()
  expect(screen.queryByText('조건에 맞는 하객이 없습니다')).not.toBeInTheDocument()
  expect(screen.getByText('하객 명부를 불러오는 중입니다')).toBeVisible()

  release()
  expect(await screen.findByTestId('guest-name')).toHaveTextContent('윤채원')
})

it('does not call the ledger empty while it is holding a filter\u0027s rows', async () => {
  const calls = api()
  let release = () => {}
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(async (url) => {
      if (url.search !== '') return HttpResponse.json<GuestParty[]>([])
      // The whole ledger, and it is slow — a reload after a filter that matched
      // nobody, which is the second half of the same bug: 400 people on file
      // and the screen announcing there is nobody at all.
      await held
      return HttpResponse.json<GuestParty[]>([guest(1, '김영수')])
    }),
    calls.headcount(counted(1)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  await userEvent.click(await screen.findByRole('button', { name: '신랑' }))
  await screen.findByText('조건에 맞는 하객이 없습니다')

  await userEvent.click(screen.getByRole('button', { name: '필터 지우기' }))

  expect(screen.queryByText('아직 등록된 하객이 없습니다')).not.toBeInTheDocument()
  expect(screen.getByText('하객 명부를 불러오는 중입니다')).toBeVisible()

  release()
  expect(await screen.findByTestId('guest-name')).toHaveTextContent('김영수')
})

it('sends a 401 on the ledger read back to log in, not to a connection message', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() => problem(401, 'UNAUTHENTICATED')),
    calls.headcount(() => problem(401, 'UNAUTHENTICATED')),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  // A 401 is a session state, not a network one: "연결을 확인하고 다시 시도해
  // 주세요" blames the connection, and 다시 시도 would 401 forever on the screen
  // the couple live on. The client answers every 401 the same way and the login
  // screen is the exit (lib/queryClient.ts).
  expect(await screen.findByRole('link', { name: '구글로 로그인' })).toBeVisible()
  expect(screen.queryByText('하객 명부를 불러오지 못했습니다')).not.toBeInTheDocument()
})

it('refetches the filtered ledger when the wedding\u0027s ledger key is invalidated', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() => HttpResponse.json<GuestParty[]>([guest(1, '김영수')])),
    calls.headcount(counted(1)),
  )

  const { queryClient } = renderWithProviders(<App />, { initialEntries: ['/'] })
  await screen.findByTestId('guest-name')
  await userEvent.click(screen.getByRole('button', { name: '신랑' }))
  await waitFor(() => expect(calls.ledgerRequests).toHaveLength(2))

  // WHAT `#135` AND EVERY GUEST MUTATION AFTER IT WILL DO: invalidate the middle
  // key, not the filter combination that happens to be on screen. Asserted here
  // rather than left as a comment, so the contract those inherit is a tested one
  // (notes/2026-08-21-decision-ledger-screen.md § Query keys).
  await act(async () => {
    await queryClient.invalidateQueries({ queryKey: ledgerQueryKey(12) })
  })

  await waitFor(() => expect(calls.ledgerRequests).toHaveLength(3))
  expect(calls.lastLedgerRequest().searchParams.get('side')).toBe('GROOM')
  // And the unfiltered list behind it — the one a guest added under a pressed
  // chip would otherwise leave stale — is marked for refetching too.
  expect(queryClient.getQueryState(guestsQueryKey(12, {}))?.isInvalidated).toBe(true)
})

/*
 * 인원수 — the number pinned above the ledger (`#17`).
 *
 * Mandatory tests, like the list beside them: the headcount display is named in
 * notes/2026-08-08-decision-frontend-testing-methodology.md, and this is the
 * number the couple takes to their venue. What is asserted here is what the
 * screen SAYS in each state, because the failure this product cannot have is a
 * number that is wrong while looking exactly like a number that is right.
 */

/**
 * The block itself — a landmark, so nothing here has to guess at a container.
 * `find`, because 원장 is not the first thing on screen: the session and the
 * couple's weddings resolve above it.
 */
async function headcount() {
  return await screen.findByRole('region', { name: '인원수' })
}

/**
 * One reading that spans two elements — "보증 3" is a label with the figure in
 * its own element, because a figure is semibold and tabular and the label is
 * neither. The default text matcher only sees an element's own text nodes, so
 * the two halves have to be put back together here.
 */
function reads(text: string) {
  return (_: string, element: Element | null) =>
    element?.textContent?.replace(/\s+/g, ' ').trim() === text
}

it('asks for the number beside the ledger rather than after it', async () => {
  const calls = api()
  let release = () => {}
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(async () => {
      await held
      return HttpResponse.json<GuestParty[]>([guest(1, '김영수')])
    }),
    calls.headcount(counted(1)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  // The number is on screen while the ledger is still in flight, so it was not
  // queued behind it: 원장 화면 opens both, in parallel, and the list response
  // carries no aggregate to wait for (docs/api-spec.md).
  expect(await within(await headcount()).findByText('1')).toBeVisible()
  expect(calls.ledgerRequests).toHaveLength(1)
  expect(screen.getByText('하객 명부를 불러오는 중입니다')).toBeVisible()

  release()
  expect(await screen.findByTestId('guest-name')).toHaveTextContent('김영수')
})

it('never draws a number it has not been given as 0', async () => {
  const calls = api()
  let release = () => {}
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() =>
      HttpResponse.json<GuestParty[]>([guest(1, '김영수'), guest(2, '박지민')]),
    ),
    calls.headcount(async () => {
      await held
      return counted(2)()
    }),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  // A ledger with two people in it and a read that has not landed are not the
  // same thing as an empty ledger, and 0 is what an empty ledger says.
  expect(within(await headcount()).queryByText('0')).not.toBeInTheDocument()
  expect(within(await headcount()).getByText('—')).toBeVisible()
  expect(await headcount()).toHaveAttribute('aria-busy', 'true')

  release()
  expect(await within(await headcount()).findByText('2')).toBeVisible()
  expect(await headcount()).toHaveAttribute('aria-busy', 'false')
})

it('says the number failed rather than showing one, and asks for it again', async () => {
  const calls = api()
  let attempt = 0
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() =>
      HttpResponse.json<GuestParty[]>([guest(1, '김영수'), guest(2, '박지민')]),
    ),
    calls.headcount(() => {
      attempt += 1
      return attempt === 1 ? problem(500, 'INTERNAL_ERROR') : counted(2)()
    }),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  expect(await screen.findByText('인원수를 불러오지 못했습니다.')).toBeVisible()
  expect(within(await headcount()).queryByText('0')).not.toBeInTheDocument()
  // Two reads, two answers: a number that did not arrive does not blank the
  // list, and the couple can still work the ledger while it is missing.
  expect(screen.getAllByTestId('guest-name')).toHaveLength(2)

  await userEvent.click(
    within(await headcount()).getByRole('button', { name: '다시 시도' }),
  )

  expect(await within(await headcount()).findByText('2')).toBeVisible()
  expect(screen.queryByText('인원수를 불러오지 못했습니다.')).not.toBeInTheDocument()
})

it('drops the number rather than keeping a stale one when a refetch fails', async () => {
  const calls = api()
  let attempt = 0
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() =>
      HttpResponse.json<GuestParty[]>([guest(1, '김영수'), guest(2, '박지민')]),
    ),
    calls.headcount(() => {
      attempt += 1
      return attempt === 1 ? counted(2)() : problem(500, 'INTERNAL_ERROR')
    }),
  )

  const { queryClient } = renderWithProviders(<App />, { initialEntries: ['/'] })
  expect(await within(await headcount()).findByText('2')).toBeVisible()

  await act(async () => {
    await queryClient.invalidateQueries({ queryKey: headcountQueryKey(12) })
  })

  // The couple would otherwise be shown a 40px figure from an earlier moment at
  // full confidence, with a 13px note beside it. `staleTime` is 0 and the window
  // regains focus, so a refetch here is ordinary rather than exotic.
  expect(await within(await headcount()).findByText('—')).toBeVisible()
  expect(within(await headcount()).queryByText('2')).not.toBeInTheDocument()
  expect(screen.getByText('인원수를 불러오지 못했습니다.')).toBeVisible()
})

it('shows 0 for a ledger with nobody in it, because that 0 was counted', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() => HttpResponse.json<GuestParty[]>([])),
    calls.headcount(counted(0)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  // Day one for every couple who just made a wedding, and a 200 — the empty
  // ledger's number is a real answer, unlike the two states above.
  expect(await screen.findByText('아직 등록된 하객이 없습니다')).toBeVisible()
  expect(within(await headcount()).getByText('0')).toBeVisible()
  expect(within(await headcount()).queryByText('—')).not.toBeInTheDocument()
})

it('draws no comparison at all while the couple has no 보증인원', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() =>
      HttpResponse.json<GuestParty[]>([guest(1, '김영수'), guest(2, '박지민')]),
    ),
    // The member is omitted, not null — and until `#8` there is no screen that
    // could set it, so this is every couple in v1.
    calls.headcount(counted(2)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  expect(await within(await headcount()).findByText('2')).toBeVisible()
  // The 굽 still says whose number 보증인원 is — that sentence is a standing
  // fact, not a comparison. What must be absent is a FIGURE to compare against.
  expect(within(await headcount()).queryByText(/보증\s*\d/)).not.toBeInTheDocument()
  expect(within(await headcount()).queryByText(/여유|초과/)).not.toBeInTheDocument()
  expect(
    within(await headcount()).queryByTestId('guarantee-meter'),
  ).not.toBeInTheDocument()
})

it('subtracts the two numbers itself when the venue has given one', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() =>
      HttpResponse.json<GuestParty[]>([guest(1, '김영수'), guest(2, '박지민')]),
    ),
    calls.headcount(counted(2, 3)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  // 대비는 화면의 뺄셈이다: the server sends two numbers and never a difference,
  // a percentage or a recommendation.
  expect(await within(await headcount()).findByText(reads('보증 3'))).toBeVisible()
  expect(within(await headcount()).getByText(reads('여유 1'))).toBeVisible()
})

it('says 초과 rather than a negative 여유 when the ledger is over the 보증인원', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() =>
      HttpResponse.json<GuestParty[]>([
        // Four people over three rows: 식대 인원 counts records, not rows.
        party(person(1, '김영수'), companion(person(1, '김영수'), 1)),
        guest(2, '박지민'),
        guest(3, '이서연'),
      ]),
    ),
    calls.headcount(counted(4, 3)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  expect(await within(await headcount()).findByText(reads('초과 1'))).toBeVisible()
  expect(within(await headcount()).queryByText(/여유/)).not.toBeInTheDocument()
})

/*
 * WHERE THE NUMBER COMES FROM AFTER A WRITE — asserted in
 * `components/AddGuestSheet.test.tsx` now, and no longer here.
 *
 * Every wedding-scoped mutation returns `{party, headcount}`, recomputed inside
 * the same transaction as the write, and the number is taken from that response
 * in the mutation's own `onSuccess` — never from a request fired beside the
 * mutation, which lands outside the window mutations are serialised in and puts
 * the out-of-order race back
 * (notes/2026-08-21-decision-query-defaults-and-mutation-ordering.md).
 *
 * A hand-written mutation stood here until `#135`, so the contract the real one
 * would inherit was tested rather than described. 하객 추가 is that real one, it
 * makes the same assertions through the screen the couple actually presses, and
 * two components named 하객 추가 on one ledger is one too many.
 */

/*
 * 접히는 원장 — 한 팀이 한 줄이고, 참석 열에는 세 번째 읽기가 있다 (`#213`,
 * notes/2026-08-23-decision-companions-become-guests.md).
 *
 * Mandatory tests: this is the ledger display. What is asserted is what the
 * screen SAYS about a party it was handed — including the one case where it is
 * required to say nothing at all and give the decision back.
 */

/** The party rows only — a member row is a `listitem` too, once one is open. */
function partyRows() {
  return screen
    .getAllByRole('listitem')
    .filter((row) => within(row).queryAllByTestId('guest-name').length > 0)
}

/** The list a row's disclosure controls, or `null` while the row is folded. */
function expansion(row: HTMLElement) {
  const caret = within(row).getByRole('button', { name: /동반 인원$/ })
  return document.getElementById(caret.getAttribute('aria-controls') ?? '')
}

/** Every person in an open party, as `이름 · 참석 여부`. */
function membersOf(row: HTMLElement) {
  const list = expansion(row)
  if (list === null) return null
  return within(list)
    .getAllByRole('listitem')
    .map((member) => (member.textContent ?? '').replace(/(참석|불참)$/, ' · $1'))
}

it('leaves a party of one with nothing to expand', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() => HttpResponse.json<GuestParty[]>([guest(1, '김영수')])),
    calls.headcount(counted(1)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  await screen.findByTestId('guest-name')

  // A party of one IS a person, and the collapsed row is already that person.
  // A disclosure over a row with nothing underneath is a control that lies.
  expect(screen.queryByRole('button', { name: /동반 인원$/ })).not.toBeInTheDocument()
})

it('folds a party into its head and expands it into its people', async () => {
  const calls = api()
  const head = person(1, '박영희', { groupCategory: 'PARENTS_GUEST' })
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() =>
      HttpResponse.json<GuestParty[]>([
        party(head, companion(head, 1), companion(head, 2)),
      ]),
    ),
    calls.headcount(counted(3)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  await screen.findByTestId('guest-name')

  // Folded: one row, named after the head, carrying the party's 인원.
  expect(renderedNames()).toEqual(['박영희'])
  const row = partyRows()[0]
  expect(within(row).getByText(reads('3명'))).toBeVisible()
  expect(membersOf(row)).toBeNull()

  await userEvent.click(within(row).getByRole('button', { name: '박영희 동반 인원' }))

  // 이름은 저장된 것이지 만들어 낸 것이 아니다 — `{대표자} 동반 N`, exactly as
  // the server wrote it once and will never write again.
  expect(membersOf(row)).toEqual([
    '박영희 · 참석',
    '박영희 동반 1 · 참석',
    '박영희 동반 2 · 참석',
  ])
  // The people rode along with the party, so expanding asked for nothing: one
  // ledger read, and it is the one this screen opened with.
  expect(calls.ledgerRequests).toHaveLength(1)

  await userEvent.click(within(row).getByRole('button', { name: '박영희 동반 인원' }))
  expect(membersOf(row)).toBeNull()
})

it('reads a mixed party as a count, and expands it rather than picking a side', async () => {
  const calls = api()
  const head = person(6, '박영희')
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() =>
      HttpResponse.json<GuestParty[]>([
        party(
          head,
          companion(head, 1),
          companion(head, 2),
          companion(head, 3, { expectedAttending: false }),
        ),
      ]),
    ),
    calls.headcount(counted(3)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  await screen.findByTestId('guest-name')
  const row = partyRows()[0]

  /*
   * A MIXED PARTY HAS NO ATTENDANCE. 참석 would be a lie about the one who
   * cannot come and 불참 a lie about the three who can, so the column states
   * the count it does know — 애매한 것은 추측하지 않는다, applied to a control
   * instead of to an import.
   */
  expect(within(row).getByText('3 / 4')).toBeVisible()
  expect(within(row).queryByText('참석')).not.toBeInTheDocument()
  expect(within(row).queryByText('불참')).not.toBeInTheDocument()

  await userEvent.click(within(row).getByRole('button', { name: /참석, 펼치기$/ }))

  // Pressing it opens the row instead of answering for four people at once,
  // and it writes nothing on the way.
  expect(membersOf(row)).toEqual([
    '박영희 · 참석',
    '박영희 동반 1 · 참석',
    '박영희 동반 2 · 참석',
    '박영희 동반 3 · 불참',
  ])
  expect(calls.ledgerRequests).toHaveLength(1)
})

it('says 참석 or 불참 only when the whole party agrees', async () => {
  const calls = api()
  const all = person(1, '김영수')
  const none = person(2, '윤채원', { expectedAttending: false })
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() =>
      HttpResponse.json<GuestParty[]>([
        party(all, companion(all, 1)),
        party(none, companion(none, 1)),
      ]),
    ),
    calls.headcount(counted(2)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  await screen.findAllByTestId('guest-name')
  const rows = partyRows()

  expect(within(rows[0]).getByText('참석')).toBeVisible()
  // 불참 is neutral and stated as plainly as 참석: a guest who cannot come is a
  // fact, not an error.
  expect(within(rows[1]).getByText('불참')).toBeVisible()
  // Neither is a control yet — tapping attendance is `#13`, and a button that
  // does nothing is worse than a readout. The mixed reading is a button today
  // only because it already has somewhere to go.
  expect(within(rows[0]).queryByRole('button', { name: '참석' })).not.toBeInTheDocument()
  expect(within(rows[1]).queryByRole('button', { name: '불참' })).not.toBeInTheDocument()
})

it('names a filtered party after its head even when the filter excluded the head', async () => {
  const calls = api()
  const head = person(1, '김영수', { expectedAttending: false })
  const brought = companion(head, 1, { expectedAttending: true })
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests((url) =>
      HttpResponse.json<GuestParty[]>([
        url.searchParams.get('attendance') === 'ATTENDING'
          ? // Filters select PEOPLE, and a party appears when any of its people
            // matched — so under 참석 this is the head's party carrying only the
            // companion, and `id`/`name` are still the head's.
            { ...party(brought), id: head.id, name: head.name }
          : party(head, brought),
      ]),
    ),
    calls.headcount(counted(1)),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })
  await screen.findByTestId('guest-name')

  await userEvent.click(screen.getByRole('button', { name: '참석' }))

  /*
   * 대표자는 불참인데 동반은 참석인 팀, under the 참석 chip. The row is named
   * after the head because that is how the couple recognises it, and the
   * figures are the server's: `size` and `attendingCount` describe the members
   * that MATCHED. Summing those over this filter is exactly 식대 인원, which is
   * why folding `members` here would produce a second, disagreeing number.
   */
  await waitFor(() => expect(renderedNames()).toEqual(['김영수']))
  const row = partyRows()[0]
  expect(within(row).getByText(reads('1명'))).toBeVisible()
  expect(within(row).getByText('참석')).toBeVisible()

  // AND THERE IS NOTHING TO EXPAND, because under this filter the party is one
  // person — the disclosure follows the `size` the server sent, not a party
  // total the client has not been told.
  expect(
    within(row).queryByRole('button', { name: /동반 인원$/ }),
  ).not.toBeInTheDocument()
})
