import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { expect, it, vi } from 'vitest'
import { App } from '../App'
import type { Headcount } from '../hooks/useHeadcount'
import type { Session } from '../hooks/useSession'
import type { Wedding } from '../hooks/useWeddings'
import type { paths } from '../lib/api-types.gen'
import { renderWithProviders } from '../test/render'
import { server } from '../test/server'

/*
 * 설정 · 파트너 초대 — the screen that mints the link, end to end inside the app
 * with only the network faked.
 *
 * THESE ARE MANDATORY TESTS: a mutation flow, and one that branches on the
 * API's error `code` (notes/2026-08-08-decision-frontend-testing-methodology.md).
 *
 * WHAT IS ASSERTED IS THE SHAPE OF THE LINK AND WHO IS OFFERED ONE. The token
 * belongs in the FRAGMENT and nowhere else — a link that put it in the path or
 * the query would be recorded in plaintext in every access log it passes
 * (notes/2026-08-22-decision-the-invite-link.md §2) — and a wedding with two
 * seated people has nobody to invite, which the API only tells us through
 * `seats[].name`.
 */

const API = 'http://localhost:8080'
const TOKEN = 'sel3ct0r.v3r1f13r'

type IssueInvite = paths['/weddings/{weddingId}/invite']['post']
/** `{token, expiresAt}` — published exactly once and never readable again. */
type IssuedInvite = IssueInvite['responses'][201]['content']['*/*']

const WAITING: Wedding['seats'] = [
  { side: 'GROOM', name: '김신랑' },
  { side: 'BRIDE', name: null },
]
const SEATED: Wedding['seats'] = [
  { side: 'GROOM', name: '김신랑' },
  { side: 'BRIDE', name: '이신부' },
]

/**
 * The wedding as the server keeps it, and the one POST that mints a link.
 *
 * IT IS STATEFUL ABOUT THE SEATS because that is the only signal the API gives
 * about whether there is anybody left to invite, and the screen has to follow
 * it: a partner who joins from their own phone turns this couple's 재발급 button
 * into a button that can only be refused.
 */
function inviteApi({
  seats = WAITING,
  held: hold = false,
}: {
  seats?: Wedding['seats']
  held?: boolean
} = {}) {
  let taken = seats
  const issued: { contentType: string | null }[] = []

  let release = () => {}
  const answering = new Promise<void>((resolve) => {
    release = resolve
  })

  return {
    issued,
    release,
    /** The partner, accepting from their own phone while this screen is open. */
    partnerJoins: () => {
      taken = SEATED
    },
    handlers: (respond?: () => Response) => [
      http.get(`${API}/auth/me`, () =>
        HttpResponse.json<Session>({ id: 7, name: '김신랑' }),
      ),
      http.get(`${API}/weddings`, () =>
        HttpResponse.json<Wedding[]>([
          { id: 12, weddingDate: '2026-10-10', seats: taken },
        ]),
      ),
      http.get(`${API}/weddings/:weddingId/headcount`, () =>
        HttpResponse.json<Headcount>({ mealHeadcount: 0 }),
      ),
      http.post(`${API}/weddings/:weddingId/invite`, async ({ request }) => {
        issued.push({ contentType: request.headers.get('Content-Type') })
        if (hold) await answering
        if (respond !== undefined) return respond()
        return HttpResponse.json<IssuedInvite>(
          { token: TOKEN, expiresAt: '2026-08-23T09:00:00Z' },
          { status: 201 },
        )
      }),
    ],
  }
}

/** A problem document as the API writes it — `code` is the only member read. */
const problem = (status: number, code: string) =>
  HttpResponse.json(
    { type: 'about:blank', title: 'Error', status, detail: 'Diagnostic.', code },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )

const settings = () => renderWithProviders(<App />, { initialEntries: ['/settings'] })
const issue = async (name = '초대 링크 만들기') =>
  await userEvent.click(await screen.findByRole('button', { name }))

it('publishes the token in the link fragment, and never anywhere else in the URL', async () => {
  const api = inviteApi()
  server.use(...api.handlers())

  settings()
  await issue()

  const link = await screen.findByText(`${window.location.origin}/invite#t=${TOKEN}`)
  expect(link).toBeInTheDocument()
  // The fragment is the only part of a URL never sent to a server. A token in
  // the path is in an access log before any of our code runs.
  expect(link.textContent).not.toContain(`?t=`)
  expect(api.issued[0].contentType).toBe('application/json')
})

it('says what a link costs: one day, and one at a time', async () => {
  const api = inviteApi()
  server.use(...api.handlers())

  settings()
  // 재발급이 이전 링크를 죽인다 — said BEFORE the couple presses it, because a
  // couple who believe they hold three live links have been misled by us
  // (notes/2026-08-22-decision-the-invite-link.md §1).
  expect(
    await screen.findByText(/새로 만들면 이전 링크는 바로 쓸 수 없게 됩니다/),
  ).toBeInTheDocument()

  await issue()

  // `expiresAt` is what is rendered — never a duration computed here.
  expect(await screen.findByText(/2026년 8월 23일/)).toBeInTheDocument()
  // And it can never be read back: only a hash is stored, so leaving this
  // screen loses the link for good (docs/api-spec.md § POST .../invite).
  expect(
    screen.getByText(/이 화면을 벗어나면 링크를 다시 볼 수 없습니다/),
  ).toBeInTheDocument()
})

it('copies the link a couple has to paste into KakaoTalk', async () => {
  const api = inviteApi()
  server.use(...api.handlers())
  const writeText = vi.fn().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
  })

  settings()
  await issue()
  await userEvent.click(await screen.findByRole('button', { name: '링크 복사' }))

  expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/invite#t=${TOKEN}`)
  expect(await screen.findByText('복사했습니다')).toBeInTheDocument()
})

it('offers nothing to a wedding whose seats are both taken', async () => {
  const api = inviteApi({ seats: SEATED })
  server.use(...api.handlers())

  settings()

  expect(await screen.findByText('두 사람 모두 참여했습니다')).toBeInTheDocument()
  // A screen state, not a rule enforced twice: `seats[].name` is the only
  // signal the API gives a fresh tab (notes/2026-08-22-decision-the-partner-invite.md §5).
  expect(
    screen.queryByRole('button', { name: '초대 링크 만들기' }),
  ).not.toBeInTheDocument()
  expect(api.issued).toHaveLength(0)
})

it('takes the button away when the partner joined while this tab was open', async () => {
  const api = inviteApi()
  server.use(...api.handlers(() => problem(409, 'PARTNER_ALREADY_JOINED')))

  settings()
  // The tab has the empty seat on screen; the partner accepts from their own
  // phone a moment later.
  const button = await screen.findByRole('button', { name: '초대 링크 만들기' })
  api.partnerJoins()
  await userEvent.click(button)

  // Not a failure to explain away — the seat is filled, which is what the
  // couple wanted. The screen re-reads the wedding and the button goes.
  expect(await screen.findByText('두 사람 모두 참여했습니다')).toBeInTheDocument()
  await waitFor(() =>
    expect(
      screen.queryByRole('button', { name: '초대 링크 만들기' }),
    ).not.toBeInTheDocument(),
  )
})

it('mints one link for a double press', async () => {
  const api = inviteApi({ held: true })
  server.use(...api.handlers())

  settings()
  const button = await screen.findByRole('button', { name: '초대 링크 만들기' })
  await userEvent.click(button)
  await waitFor(() => expect(api.issued).toHaveLength(1))
  // Still in flight. Serialising mutations delays a second press and never
  // refuses it, and a second mint would kill the link the first one just made
  // — the couple would be holding a dead link they watched us make.
  expect(button).toBeDisabled()
  await userEvent.click(button)

  api.release()
  expect(
    await screen.findByText(`${window.location.origin}/invite#t=${TOKEN}`),
  ).toBeInTheDocument()
  expect(api.issued).toHaveLength(1)
})

it('says a failed mint failed, and leaves the button pressable', async () => {
  const api = inviteApi()
  server.use(...api.handlers(() => HttpResponse.json({}, { status: 500 })))

  settings()
  await issue()

  expect(await screen.findByRole('alert')).toHaveTextContent(
    '초대 링크를 만들지 못했습니다. 잠시 후 다시 시도해 주세요.',
  )
  expect(screen.getByRole('button', { name: '초대 링크 만들기' })).toBeEnabled()
})
