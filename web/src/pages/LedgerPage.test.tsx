import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { expect, it } from 'vitest'
import { App } from '../App'
import type { Guest } from '../hooks/useGuests'
import type { Session } from '../hooks/useSession'
import type { Wedding } from '../hooks/useWeddings'
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
  groomName: '김신랑',
  brideName: '이신부',
}

/** A row as the API returns it, typed from the generated document. */
function guest(id: number, name: string, overrides: Partial<Guest> = {}): Guest {
  return {
    id,
    name,
    side: 'GROOM',
    groupCategory: 'OTHER',
    groupLabel: null,
    contact: null,
    accessibilityNote: null,
    expectedAttending: true,
    expectedPartySize: 1,
    ...overrides,
  }
}

/**
 * The three calls this screen makes, with every ledger request kept in the order
 * it arrived — the URL included, which is the whole point.
 */
function api() {
  const ledgerRequests: URL[] = []
  return {
    ledgerRequests,
    /** The last ledger URL the server was asked for. */
    lastLedgerRequest: () => ledgerRequests[ledgerRequests.length - 1],
    me: () =>
      http.get(`${API}/auth/me`, () =>
        HttpResponse.json<Session>({ id: 7, name: '김테스터' }),
      ),
    weddings: (respond: () => Response = () => HttpResponse.json<Wedding[]>([WEDDING])) =>
      http.get(`${API}/weddings`, () => respond()),
    guests: (respond: (url: URL) => Response) =>
      http.get(`${API}/weddings/:weddingId/guests`, ({ request }) => {
        const url = new URL(request.url)
        ledgerRequests.push(url)
        return respond(url)
      }),
  }
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
      HttpResponse.json<Guest[]>([
        guest(1, '한지우'),
        guest(2, '박지민'),
        guest(3, '김영수'),
        guest(4, '이서연'),
      ]),
    ),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  await screen.findAllByTestId('guest-name')
  // 이름 검색 is post-v1, so the order IS how a couple finds a person in v1.
  expect(renderedNames()).toEqual(['김영수', '박지민', '이서연', '한지우'])
  // The wedding the ledger belongs to, so a person in two weddings can see
  // which one they are looking at.
  expect(screen.getByText('김신랑 · 이신부')).toBeVisible()
  // `[0]` of GET /weddings, which is newest first and is contract.
  expect(calls.lastLedgerRequest().pathname).toBe('/weddings/12/guests')
})

it('sends no filter parameter at all while both sides and both answers are wanted', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() => HttpResponse.json<Guest[]>([])),
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
    calls.guests(() => HttpResponse.json<Guest[]>([])),
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
    calls.guests(() => HttpResponse.json<Guest[]>([])),
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
    calls.guests(() => HttpResponse.json<Guest[]>([])),
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
      HttpResponse.json<Guest[]>(
        url.search === '' ? [guest(1, '김영수', { side: 'GROOM' })] : [],
      ),
    ),
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

it('shows each guest with the side, group and party size the ledger is read by', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() =>
      HttpResponse.json<Guest[]>([
        guest(1, '김영수', {
          side: 'GROOM',
          groupCategory: 'PARENTS_GUEST',
          groupLabel: '아버지 회사 동료',
          expectedPartySize: 2,
        }),
        guest(2, '윤채원', {
          side: 'BRIDE',
          groupCategory: 'FRIEND',
          expectedAttending: false,
        }),
      ]),
    ),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  const rows = await screen.findAllByRole('listitem')
  expect(within(rows[0]).getByText('신랑')).toBeVisible()
  expect(within(rows[0]).getByText('혼주 손님')).toBeVisible()
  expect(within(rows[0]).getByText('아버지 회사 동료')).toBeVisible()
  expect(within(rows[0]).getByText('2명')).toBeVisible()
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
        : HttpResponse.json<Guest[]>([guest(1, '김영수')])
    }),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  expect(await screen.findByText('원장을 불러오지 못했습니다')).toBeVisible()

  await userEvent.click(screen.getByRole('button', { name: '다시 시도' }))

  expect(await screen.findByTestId('guest-name')).toHaveTextContent('김영수')
})

it('turns a 404 into the same failure as any other, never into an existence hint', async () => {
  const calls = api()
  server.use(
    calls.me(),
    calls.weddings(),
    calls.guests(() => problem(404, 'WEDDING_NOT_FOUND')),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  // No such wedding, not the caller's, and deleted are one answer on the server
  // (docs/api-spec.md), so they are one answer on the screen too.
  expect(await screen.findByText('원장을 불러오지 못했습니다')).toBeVisible()
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
    calls.guests(() => HttpResponse.json<Guest[]>([guest(1, '김영수')])),
  )

  renderWithProviders(<App />, { initialEntries: ['/'] })

  // Which of the two reads failed is not the couple's problem: they asked for
  // 원장 and did not get it. One message, one way out.
  expect(await screen.findByText('원장을 불러오지 못했습니다')).toBeVisible()

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
  expect(await screen.findByRole('heading', { name: '웨딩 만들기' })).toBeVisible()
  expect(calls.ledgerRequests).toHaveLength(0)
})
